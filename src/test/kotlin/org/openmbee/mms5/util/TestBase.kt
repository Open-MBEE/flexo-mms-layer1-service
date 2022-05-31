package org.openmbee.mms5.util

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.testing.*
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import com.github.stefanbirkner.systemlambda.SystemLambda.*;
import com.typesafe.config.ConfigFactory
import io.ktor.config.*
import io.ktor.server.engine.*
import org.apache.jena.query.QueryExecutionFactory
import org.apache.jena.query.QuerySolution
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.riot.Lang
import org.apache.jena.riot.RDFDataMgr
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.openmbee.mms5.ROOT_CONTEXT
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.*
import kotlin.collections.ArrayList
import kotlin.test.assertEquals

/**
 * Base class for JUnit tests with helpers for setting up a test environment.
 *
 * - Sets up application using src/main/resources/application.conf.example configuration
 * - Runs an embedded Fuseki server using an in-memory data store (once for the whole class)
 *    - If MMS5_QUERY_URL and MMS5_UPDATE_URL environment variables are set, the tests will use those endpoints and
 *      not run the embedded Fuseki server. Before each test all Example data will still be reset, but after
 *      the test is run the data will be left as is for inspection.
 * - Before each test is run clears out the Example MMS graphs and re-initialize with src/test/resources/init.trig
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class TestBase {

    /**
     * Backend RDF store.
     */
    private val backend = FusekiBackend()

    /**
     * Determines whether to run the Fuseki backend by lack of MMS5_QUERY_URL and MMS5_UPDATE_URL environment variables.
     */
    private val runSparqlBackend = System.getenv("MMS5_QUERY_URL") == null && System.getenv("MMS5_UPDATE_URL") == null && System.getenv("MMS5_GRAPH_STORE_PROTOCOL_URL") == null

    /**
     * Contents of init.trig used to reset database
     */
    private val initTrig = Files.readAllBytes(FileSystems.getDefault().getPath("src", "test", "resources", "cluster.trig"))
    
    /**
     * Standard SPARQL prefixes
     */
    private val sparqlPrefixes = """
        PREFIX mms-txn: <https://mms.openmbee.org/rdf/ontology/txn.>
        PREFIX mo: <${ROOT_CONTEXT}/orgs/openmbee>
        PREFIX mms-datatype: <https://mms.openmbee.org/rdf/datatypes/>
        PREFIX owl: <http://www.w3.org/2002/07/owl#>
        PREFIX m-object: <${ROOT_CONTEXT}/objects/>
        PREFIX mt: <${ROOT_CONTEXT}/transactions/cb059b78-f239-453d-b4e3-e1b081e8390f>
        PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
        PREFIX mu: <${ROOT_CONTEXT}/users/root>
        PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
        PREFIX m: <${ROOT_CONTEXT}>
        PREFIX m-group: <${ROOT_CONTEXT}/groups/>
        PREFIX mms-object: <https://mms.openmbee.org/rdf/objects/>
        PREFIX mms: <https://mms.openmbee.org/rdf/ontology/>
        PREFIX m-org: <${ROOT_CONTEXT}/orgs/>
        PREFIX dct: <http://purl.org/dc/terms/>
        PREFIX m-policy: <${ROOT_CONTEXT}/policies/>
        PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
        PREFIX m-user: <${ROOT_CONTEXT}/users/>
        PREFIX m-graph: <${ROOT_CONTEXT}/graphs/>
        PREFIX sesame: <http://www.openrdf.org/schema/sesame#>
        PREFIX fn: <http://www.w3.org/2005/xpath-functions#>
        PREFIX foaf: <http://xmlns.com/foaf/0.1/>
        PREFIX dc: <http://purl.org/dc/elements/1.1/>
        PREFIX hint: <http://www.bigdata.com/queryHints#>
        PREFIX bd: <http://www.bigdata.com/rdf#>
        PREFIX bds: <http://www.bigdata.com/rdf/search#>

    """.trimIndent()

    private fun testEnv(): ApplicationEngineEnvironment {
        return createTestEnvironment {
            javaClass.classLoader.getResourceAsStream("application.conf.example")?.let { it ->
                InputStreamReader(it).use { iit ->
                    config = HoconApplicationConfig(ConfigFactory.parseReader(iit).resolve())
                }
            }
        }
    }

    /**
     * Run a SPARQL query on a Turtle response body and return
     * all results. Queries will be prepended with all the sparqlPrefixes.
     */
    private fun findAllInResponse(call: TestApplicationCall, sparql: String): List<QuerySolution> {
        val model = ModelFactory.createDefaultModel()
        RDFDataMgr.read(model, ByteArrayInputStream(call.response.byteContent), Lang.TURTLE)
        val results = ArrayList<QuerySolution>()
        QueryExecutionFactory.create(sparqlPrefixes + "\n" + sparql, model).execSelect().forEachRemaining {
            results.add(it)
        }
        return results
    }

    private fun findAllInBackend(sparql: String): List<QuerySolution> {
        val queryUrl = if (runSparqlBackend) {
            backend.getQueryUrl()
        } else {
            System.getenv("MMS5_QUERY_URL")
        }
        val results = ArrayList<QuerySolution>()
        QueryExecutionFactory.sparqlService(queryUrl, sparqlPrefixes + "\n" + sparql).execSelect().forEachRemaining {
            results.add(it)
        }
        return results
    }

    fun findOneInBackend(sparql: String): QuerySolution {
        val all = findAllInBackend(sparql)
        assertEquals(1, all.size, "Expected single result for query $sparql")
        return all[0]
    }

    /**
     * Run a SPARQL query on a Turtle response body and assert
     * there's only result, returning it. Queries will be prepended with all the sparqlPrefixes.
     */
    fun findOneInResponse(call: TestApplicationCall, sparql: String): QuerySolution {
        val all = findAllInResponse(call, sparql)
        assertEquals(1, all.size, "Expected single result for query $sparql")
        return all[0]
    }

    /**
     * Extension function to add a Turtle request body with appropriate Content-Type
     * to a test request
     */
    fun TestApplicationRequest.setTurtleBody(turtle: String) {
        addHeader("Content-Type", "text/turtle")
        setBody(turtle)
    }

    /**
     * Extension function to add a SPARQL update request body with appropriate Content-Type
     * to a test request
     */
    fun TestApplicationRequest.setSparqlUpdateBody(sparqlUpdate: String) {
        addHeader("Content-Type", "application/sparql-update")
        setBody(sparqlUpdate)
    }

    /**
     * Extension function to add an Authorization: header with a Bearer token for the
     * given username to a test request
     */
    fun TestApplicationRequest.addAuthorizationHeader(username: String, groups: List<String>) {
        addHeader("Authorization", authorization(username, groups))
    }

    /**
     * Generate an Authorization: header Bearer token value for the given username.
     */
    private fun authorization(username: String, groups: List<String>): String {
        val testEnv = testEnv()
        val jwtAudience = testEnv.config.property("jwt.audience").getString()
        val issuer = testEnv.config.property("jwt.domain").getString()
        val secret = testEnv.config.property("jwt.secret").getString()
        val expires = Date(System.currentTimeMillis() + (1 * 24 * 60 * 60 * 1000))
        return "Bearer " + JWT.create()
            .withAudience(jwtAudience)
            .withIssuer(issuer)
            .withClaim("username", username)
            .withClaim("groups", groups)
            .withExpiresAt(expires)
            .sign(Algorithm.HMAC256(secret))
    }

    /**
     * Starts the SPARQL backend once before all the tests in the class are run,
     * unless the MMS5_QUERY_URL and MMS5_UPDATE_URL environment variables are
     * set.
     */
    @BeforeAll
    fun startBackend() {
        if (runSparqlBackend) {
            backend.start()
        }
    }

    /**
     * Before each test is run drop all the Example MMS graphs and re-initialize with init.trig
     */
    @BeforeEach
    fun reset() {
        // Drop MMS graphs
        // Initalize with init.trig
        val updateUrl = if (runSparqlBackend) {
            backend.getUpdateUrl()
        } else {
            System.getenv("MMS5_UPDATE_URL")
        }
        val dropSparql = """
            PREFIX m-graph: <${ROOT_CONTEXT}/graphs/>
            DROP GRAPH m-graph:Schema ;
            DROP GRAPH m-graph:Cluster ;
            DROP GRAPH m-graph:AccessControl.Agents ;
            DROP GRAPH m-graph:AccessControl.Policies ;
            DROP GRAPH m-graph:AccessControl.Definitions ;
        """.trimIndent()
        val dropRequest = HttpRequest.newBuilder()
            .uri(URI(updateUrl))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("update=" + URLEncoder.encode(dropSparql, StandardCharsets.UTF_8)))
            .build()
        val dropResponse = HttpClient.newHttpClient().send(dropRequest, BodyHandlers.ofString())
        assertEquals(200, dropResponse.statusCode(), "Drop graphs successful")

        // Initalize with init.trig
        val uploadUrl = if (runSparqlBackend) {
            backend.getUploadUrl()
        } else {
            System.getenv("MMS5_GRAPH_STORE_PROTOCOL_URL")
        }
        val loadRequest = HttpRequest.newBuilder()
            .uri(URI(uploadUrl))
            .header("Content-Type", "application/trig")
            .POST(HttpRequest.BodyPublishers.ofByteArray(initTrig))
            .build()
        println("UPLOAD URL: $uploadUrl")
        println("HEADERS: " + loadRequest.headers().toString())
        val loadResponse = HttpClient.newHttpClient().send(loadRequest, BodyHandlers.ofString())
        println("RESPONSE: " + loadResponse.body())
        assertEquals(200, loadResponse.statusCode(), "Load cluster.trig successful")

        // Create policy for super admin
        val policyCreate = """
            PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            PREFIX mms: <https://mms.openmbee.org/rdf/ontology/>
            PREFIX mms-object: <https://mms.openmbee.org/rdf/objects/>
            PREFIX m: <${ROOT_CONTEXT}/>
            PREFIX m-graph: <${ROOT_CONTEXT}/graphs/>
            PREFIX m-user: <${ROOT_CONTEXT}/users/>
            PREFIX m-group: <${ROOT_CONTEXT}/groups/>
            PREFIX m-policy: <${ROOT_CONTEXT}/policies/>
            insert data {
                graph m-graph:AccessControl.Policies {
                    m-policy:TestSuperAdminPolicyName a mms:Policy ;
                        mms:subject m-group:SuperAdmins ;
                        mms:scope m: ;
                        mms:role 
                            mms-object:Role.AdminAccessControl, 
                            mms-object:Role.AdminCluster, 
                            mms-object:Role.AdminMetadata, 
                            mms-object:Role.AdminModel ;
                        .
                }
                graph m-graph:AccessControl.Definitions {
                    mms-object:Role.AdminAccessControl a mms:Role ;
                        mms:permits mms-object:Permission.DeleteAccessControl .
                    mms-object:Role.AdminCluster a mms:Role ;
                        mms:permits mms-object:Permission.DeleteCluster .
                    mms-object:Role.AdminMetadata a mms:Role ;
                        mms:permits mms-object:Permission.DeleteMetadata .
                    mms-object:Role.AdminModel a mms:Role ;
                        mms:permits mms-object:Permission.DeleteModel .
                }
            }
        """.trimIndent()
        val policyRequest = HttpRequest.newBuilder()
            .uri(URI(updateUrl))
            .header("Content-Type", "application/sparql-update")
            .POST(HttpRequest.BodyPublishers.ofString(URLEncoder.encode(policyCreate, StandardCharsets.UTF_8)))
            .build()
        val policyResponse = HttpClient.newHttpClient().send(policyRequest, BodyHandlers.ofString())
        println("policyResponse: " + policyResponse.body())
        assertEquals(200, policyResponse.statusCode(), "Policy creation successful")
    }

    /**
     * Set up the test environment (application server and required environment variables to
     * conect to SPARQL backend) and run a test body in the context of that environment.
     */
    fun <R> withTestEnvironment(test: TestApplicationEngine.() -> R): R {
        var result: R? = null
        return if (runSparqlBackend) {
            withEnvironmentVariable("MMS5_QUERY_URL", backend.getQueryUrl())
                .and("MMS5_UPDATE_URL", backend.getUpdateUrl())
                .execute {
                    withApplication(testEnv()) {
                        result = test()
                    }
                }
            result!!
        } else {
            withApplication(testEnv()) {
                result = test()
            }
            result!!
        }
    }

    /**
     * After all tests finish, stop the SPARQL backend.
     */
    @AfterAll
    fun stopBackend() {
        if (runSparqlBackend) backend.stop()
    }
}
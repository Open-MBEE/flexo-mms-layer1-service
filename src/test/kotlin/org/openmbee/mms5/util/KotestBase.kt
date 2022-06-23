package org.openmbee.mms5.util

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.testing.*
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import com.github.stefanbirkner.systemlambda.SystemLambda.*;
import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.system.withEnvironment
import io.ktor.config.*
import io.ktor.http.*
import io.ktor.server.engine.*
import org.apache.jena.rdfconnection.RDFConnection
import org.apache.jena.sparql.exec.http.UpdateExecutionHTTP
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import java.io.InputStreamReader
import java.util.*

import org.openmbee.mms5.prefixesFor


data class AuthObject (
    val username: String = "",
    val groups: List<String> = listOf("")
)

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
abstract class KotestBase : StringSpec() {

    private val rootAuth = AuthObject("root", listOf("super_admins"))

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
    private val initTrig = KotestBase::class.java.getResource("/cluster.trig")?.readText()
    
    /**
     * Standard SPARQL prefixes
     */
    private val sparqlPrefixes = prefixesFor().toString()



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
     * Extension function to add a Turtle request body with appropriate Content-Type
     * to a test request
     */
    fun TestApplicationRequest.setTurtleBody(body: String) {
        addHeader("Content-Type", "text/turtle")
        setBody(body)
    }

    /**
     * Extension function to add a SPARQL update request body with appropriate Content-Type
     * to a test request
     */
    fun TestApplicationRequest.setSparqlUpdateBody(body: String) {
        addHeader("Content-Type", "application/sparql-update")
        setBody(body)
    }

    /**
     * Extension function to add an Authorization: header with a Bearer token for the
     * given username to a test request
     */
    fun TestApplicationRequest.addAuthorizationHeader(auth: AuthObject) {
        addHeader("Authorization", authorization(auth))
    }

    /**
     * Generate an Authorization: header Bearer token value for the given username.
     */
    private fun authorization(auth: AuthObject): String {
        val testEnv = testEnv()
        val jwtAudience = testEnv.config.property("jwt.audience").getString()
        val issuer = testEnv.config.property("jwt.domain").getString()
        val secret = testEnv.config.property("jwt.secret").getString()
        val expires = Date(System.currentTimeMillis() + (1 * 24 * 60 * 60 * 1000))
        return "Bearer " + JWT.create()
            .withAudience(jwtAudience)
            .withIssuer(issuer)
            .withClaim("username", auth.username)
            .withClaim("groups", auth.groups)
            .withExpiresAt(expires)
            .sign(Algorithm.HMAC256(secret))
    }

    /**
     * Starts the SPARQL backend once before all the tests in the class are run,
     * unless the MMS5_QUERY_URL and MMS5_UPDATE_URL environment variables are
     * set.
     */
    @BeforeAll
    @Order(1)
    fun startBackend() {
        if (runSparqlBackend) {
            backend.start()
        }
    }

    /**
     * Before each test is run drop all the Example MMS graphs and re-initialize with init.trig
     */
    @BeforeAll
    @Order(2)
    fun reset() {
        // drop all graphs
        UpdateExecutionHTTP.service(backend.getUpdateUrl()).update("drop all").execute()

        // reinitalize with cluster.trig
        RDFConnection.connect(backend.getGspdUrl()).use {
            it.putDataset("cluster.trig")
        }
    }

    /**
     * Set up the test environment (application server and required environment variables to
     * connect to SPARQL backend) and run a test body in the context of that environment.
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

    fun doCreateOrg(auth: AuthObject, orgId: String, orgName: String): TestApplicationCall {
        return withTestEnvironment {
            handleRequest(HttpMethod.Put, "/orgs/$orgId") {
                addAuthorizationHeader(auth)
                setTurtleBody("""
                    <> dct:title "$orgName"@en ;
                """.trimIndent())
            }
        }
    }

    fun doGetOrg(auth: AuthObject, orgId: String? = "", headers: Map<String, String>? = null): TestApplicationCall {
        return withTestEnvironment {
            handleRequest(HttpMethod.Get, "/orgs/$orgId") {
                addAuthorizationHeader(auth)
                headers?.forEach { header ->
                    addHeader(header.key, header.value)
                }
            }
        }
    }

    /**
     * After all tests finish, stop the SPARQL backend.
     */
    @AfterAll
    fun stopBackend() {
        if (runSparqlBackend) backend.stop()
    }


    fun <R> withTest(test: TestApplicationEngine.() -> R) : R {
        return withEnvironment(mapOf(
            "MMS5_QUERY_URL" to backend.getQueryUrl(),
            "MMS5_UPDATE_URL" to backend.getQueryUrl(),
            "MMS5_GRAPH_STORE_PROTOCOL_URL" to backend.getGspdUrl(),
        )) {
            withApplication(testEnv()) {
                test()
            }
        }
    }

    fun TestApplicationEngine.request(method: HttpMethod, uri: String, setup: TestApplicationRequest.() -> Unit) {
        handleRequest(method, uri) {
            addHeader("Authorization", authorization(rootAuth))
            setup()
        }
    }

    fun TestApplicationEngine.head(uri: String, setup: TestApplicationRequest.() -> Unit) {
        this.request(HttpMethod.Head, uri, setup)
    }

    fun TestApplicationEngine.get(uri: String, setup: TestApplicationRequest.() -> Unit) {
        this.request(HttpMethod.Get, uri, setup)
    }

    fun TestApplicationEngine.post(uri: String, setup: TestApplicationRequest.() -> Unit) {
        this.request(HttpMethod.Post, uri, setup)
    }

    fun TestApplicationEngine.put(uri: String, setup: TestApplicationRequest.() -> Unit) {
        this.request(HttpMethod.Put, uri, setup)
    }

    fun TestApplicationEngine.patch(uri: String, setup: TestApplicationRequest.() -> Unit) {
        this.request(HttpMethod.Patch, uri, setup)
    }

    fun TestApplicationEngine.delete(uri: String, setup: TestApplicationRequest.() -> Unit) {
        this.request(HttpMethod.Delete, uri, setup)
    }
}

package org.openmbee.mms5.util
//
// import com.auth0.jwt.JWT
// import com.auth0.jwt.algorithms.Algorithm
// import io.ktor.server.testing.*
// import org.junit.jupiter.api.AfterAll
// import org.junit.jupiter.api.BeforeAll
// import com.github.stefanbirkner.systemlambda.SystemLambda.*;
// import com.typesafe.config.ConfigFactory
// import io.ktor.config.*
// import io.ktor.http.*
// import io.ktor.server.engine.*
// import org.apache.jena.query.QueryExecutionFactory
// import org.apache.jena.query.QuerySolution
// import org.apache.jena.rdf.model.ModelFactory
// import org.apache.jena.rdfconnection.RDFConnection
// import org.apache.jena.riot.Lang
// import org.apache.jena.riot.RDFDataMgr
// import org.apache.jena.sparql.exec.http.QueryExecutionHTTP
// import org.apache.jena.sparql.exec.http.UpdateExecutionHTTP
// import org.junit.Assert.assertEquals
// import org.junit.jupiter.api.Order
// import org.junit.jupiter.api.TestInstance
// import java.io.ByteArrayInputStream
// import java.io.InputStreamReader
// import java.util.*
// import kotlin.collections.ArrayList
//
// import org.openmbee.mms5.prefixesFor
//
// /**
//  * Base class for JUnit tests with helpers for setting up a test environment.
//  *
//  * - Sets up application using src/main/resources/application.conf.example configuration
//  * - Runs an embedded Fuseki server using an in-memory data store (once for the whole class)
//  *    - If MMS5_QUERY_URL and MMS5_UPDATE_URL environment variables are set, the tests will use those endpoints and
//  *      not run the embedded Fuseki server. Before each test all Example data will still be reset, but after
//  *      the test is run the data will be left as is for inspection.
//  * - Before each test is run clears out the Example MMS graphs and re-initialize with src/test/resources/init.trig
//  */
// @TestInstance(TestInstance.Lifecycle.PER_CLASS)
// abstract class TestBase {
//
//     /**
//      * Backend RDF store.
//      */
//     private val backend = FusekiBackend()
//
//     /**
//      * Determines whether to run the Fuseki backend by lack of MMS5_QUERY_URL and MMS5_UPDATE_URL environment variables.
//      */
//     private val runSparqlBackend = System.getenv("MMS5_QUERY_URL") == null && System.getenv("MMS5_UPDATE_URL") == null && System.getenv("MMS5_GRAPH_STORE_PROTOCOL_URL") == null
//
//     /**
//      * Contents of init.trig used to reset database
//      */
//     private val initTrig = KotestBase::class.java.getResource("/cluster.trig")?.readText()
//
//     /**
//      * Standard SPARQL prefixes
//      */
//     private val sparqlPrefixes = prefixesFor().toString()
//
//     private fun testEnv(): ApplicationEngineEnvironment {
//         return createTestEnvironment {
//             javaClass.classLoader.getResourceAsStream("application.conf.example")?.let { it ->
//                 InputStreamReader(it).use { iit ->
//                     config = HoconApplicationConfig(ConfigFactory.parseReader(iit).resolve())
//                 }
//             }
//         }
//     }
//
//     /**
//      * Run a SPARQL query on a Turtle response body and return
//      * all results. Queries will be prepended with all the sparqlPrefixes.
//      */
//     private fun findAllInResponse(call: TestApplicationCall, sparql: String): List<QuerySolution> {
//         val model = ModelFactory.createDefaultModel()
//         RDFDataMgr.read(model, ByteArrayInputStream(call.response.byteContent), Lang.TURTLE)
//         val results = ArrayList<QuerySolution>()
//         QueryExecutionFactory.create(sparqlPrefixes + "\n" + sparql, model).execSelect().forEachRemaining {
//             results.add(it)
//         }
//         return results
//     }
//
//     private fun findAllInBackend(sparql: String): List<QuerySolution> {
//         val queryUrl = if (runSparqlBackend) {
//             backend.getQueryUrl()
//         } else {
//             System.getenv("MMS5_QUERY_URL")
//         }
//         val results = ArrayList<QuerySolution>()
//         QueryExecutionHTTP.service(queryUrl, sparqlPrefixes + "\n" + sparql).execSelect().forEachRemaining {
//             results.add(it)
//         }
//         return results
//     }
//
//     fun findOneInBackend(sparql: String): QuerySolution {
//         val all = findAllInBackend(sparql)
//         assertEquals(1, all.size, "Expected single result for query $sparql")
//         return all[0]
//     }
//
//     /**
//      * Run a SPARQL query on a Turtle response body and assert
//      * there's only result, returning it. Queries will be prepended with all the sparqlPrefixes.
//      */
//     fun findOneInResponse(call: TestApplicationCall, sparql: String): QuerySolution {
//         val all = findAllInResponse(call, sparql)
//         assertEquals(1, all.size, "Expected single result for query $sparql")
//         return all[0]
//     }
//
//     /**
//      * Extension function to add a Turtle request body with appropriate Content-Type
//      * to a test request
//      */
//     fun TestApplicationRequest.setTurtleBody(turtle: String) {
//         addHeader("Content-Type", "text/turtle")
//         setBody(turtle)
//     }
//
//     /**
//      * Extension function to add a SPARQL update request body with appropriate Content-Type
//      * to a test request
//      */
//     fun TestApplicationRequest.setSparqlUpdateBody(sparqlUpdate: String) {
//         addHeader("Content-Type", "application/sparql-update")
//         setBody(sparqlUpdate)
//     }
//
//     /**
//      * Extension function to add an Authorization: header with a Bearer token for the
//      * given username to a test request
//      */
//     fun TestApplicationRequest.addAuthorizationHeader(auth: AuthObject) {
//         addHeader("Authorization", authorization(auth))
//     }
//
//     /**
//      * Generate an Authorization: header Bearer token value for the given username.
//      */
//     private fun authorization(auth: AuthObject): String {
//         val testEnv = testEnv()
//         val jwtAudience = testEnv.config.property("jwt.audience").getString()
//         val issuer = testEnv.config.property("jwt.domain").getString()
//         val secret = testEnv.config.property("jwt.secret").getString()
//         val expires = Date(System.currentTimeMillis() + (1 * 24 * 60 * 60 * 1000))
//         return "Bearer " + JWT.create()
//             .withAudience(jwtAudience)
//             .withIssuer(issuer)
//             .withClaim("username", auth.username)
//             .withClaim("groups", auth.groups)
//             .withExpiresAt(expires)
//             .sign(Algorithm.HMAC256(secret))
//     }
//
//     /**
//      * Starts the SPARQL backend once before all the tests in the class are run,
//      * unless the MMS5_QUERY_URL and MMS5_UPDATE_URL environment variables are
//      * set.
//      */
//     @BeforeAll
//     @Order(1)
//     fun startBackend() {
//         if (runSparqlBackend) {
//             backend.start()
//         }
//     }
//
//     /**
//      * Before each test is run drop all the Example MMS graphs and re-initialize with init.trig
//      */
//     @BeforeAll
//     @Order(2)
//     fun reset() {
//         // Drop MMS graphs
//         // Initalize with init.trig
//         val updateUrl = if (runSparqlBackend) {
//             backend.getUpdateUrl()
//         } else {
//             System.getenv("MMS5_UPDATE_URL")
//         }
//         UpdateExecutionHTTP.service(updateUrl).update("drop all").execute()
//         // val dropSparql = "drop all"
//         // val dropRequest = HttpRequest.newBuilder()
//         //     .uri(URI(updateUrl))
//         //     .header("Content-Type", "application/x-www-form-urlencoded")
//         //     .POST(HttpRequest.BodyPublishers.ofString("update=" + URLEncoder.encode(dropSparql, StandardCharsets.UTF_8)))
//         //     .build()
//         // val dropResponse = HttpClient.newHttpClient().send(dropRequest, BodyHandlers.ofString())
//         // assertEquals(200, dropResponse.statusCode(), "Drop graphs")
//
//         // Initalize with init.trig
//         val gspUrl = if (runSparqlBackend) {
//             backend.getGspdUrl()
//         } else {
//             System.getenv("MMS5_GRAPH_STORE_PROTOCOL_URL")
//         }
//
//         RDFConnection.connect(gspUrl).use {
//             it.putDataset("cluster.trig")
//         }
//
//         // val loadRequest = HttpRequest.newBuilder()
//         //     .uri(URI(gspUrl))
//         //     .header("Content-Type", "application/trig")
//         //     .POST(HttpRequest.BodyPublishers.ofByteArray(initTrig))
//         //     .build()
//         // val loadResponse = HttpClient.newHttpClient().send(loadRequest, BodyHandlers.ofString())
//         // assertEquals(200, loadResponse.statusCode(), "Load cluster.trig")
//     }
//
//     /**
//      * Set up the test environment (application server and required environment variables to
//      * connect to SPARQL backend) and run a test body in the context of that environment.
//      */
//     fun <R> withTestEnvironment(test: TestApplicationEngine.() -> R): R {
//         var result: R? = null
//         return if (runSparqlBackend) {
//             withEnvironmentVariable("MMS5_QUERY_URL", backend.getQueryUrl())
//                 .and("MMS5_UPDATE_URL", backend.getUpdateUrl())
//                 .execute {
//                     withApplication(testEnv()) {
//                         result = test()
//                     }
//                 }
//             result!!
//         } else {
//             withApplication(testEnv()) {
//                 result = test()
//             }
//             result!!
//         }
//     }
//
//     fun doCreateOrg(auth: AuthObject, orgId: String, orgName: String): TestApplicationCall {
//         return withTestEnvironment {
//             handleRequest(HttpMethod.Put, "/orgs/$orgId") {
//                 addAuthorizationHeader(auth)
//                 setTurtleBody("""
//                     <> dct:title "$orgName"@en ;
//                 """.trimIndent())
//             }
//         }
//     }
//
//     fun doGetOrg(auth: AuthObject, orgId: String? = "", headers: Map<String, String>? = null): TestApplicationCall {
//         return withTestEnvironment {
//             handleRequest(HttpMethod.Get, "/orgs/$orgId") {
//                 addAuthorizationHeader(auth)
//                 headers?.forEach { header ->
//                     addHeader(header.key, header.value)
//                 }
//             }
//         }
//     }
//
//     /**
//      * After all tests finish, stop the SPARQL backend.
//      */
//     @AfterAll
//     fun stopBackend() {
//         if (runSparqlBackend) backend.stop()
//     }
// }
//
// data class AuthObject (
//     val username: String = "",
//     val groups: List<String> = listOf("")
// )
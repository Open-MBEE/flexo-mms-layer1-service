package org.openmbee.mms5.util

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.system.withEnvironment
import io.ktor.config.*
import io.ktor.http.*
import io.ktor.server.engine.*
import io.ktor.server.testing.*
import org.apache.jena.rdfconnection.RDFConnection
import org.apache.jena.sparql.exec.http.UpdateExecutionHTTP
import java.io.InputStreamReader
import java.util.*


data class AuthStruct (
    val username: String = "",
    val groups: List<String> = listOf("")
)

val rootAuth = AuthStruct("root", listOf("super_admins"))

val backend = FusekiBackend()


fun localIri(suffix: String): String {
    return "http://layer1-service/$suffix"
}

/**
 * Load test environment from application.conf.example resource
 */
fun testEnv(): ApplicationEngineEnvironment {
    return createTestEnvironment {
        javaClass.classLoader.getResourceAsStream("application.conf.example")?.let { it ->
            InputStreamReader(it).use { iit ->
                config = HoconApplicationConfig(ConfigFactory.parseReader(iit).resolve())
            }
        }
    }
}

/**
 * Generate an Authorization: header Bearer token value for the given username.
 */
private fun authorization(auth: AuthStruct): String {
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

fun TestApplicationEngine.request(method: HttpMethod, uri: String, setup: TestApplicationRequest.() -> Unit): TestApplicationCall {
    return handleRequest(method, uri) {
        addHeader("Authorization", authorization(rootAuth))
        setup()
    }
}

fun TestApplicationEngine.head(uri: String, setup: TestApplicationRequest.() -> Unit): TestApplicationCall {
    return this.request(HttpMethod.Head, uri, setup)
}

fun TestApplicationEngine.get(uri: String, setup: TestApplicationRequest.() -> Unit): TestApplicationCall {
    return this.request(HttpMethod.Get, uri, setup)
}

fun TestApplicationEngine.post(uri: String, setup: TestApplicationRequest.() -> Unit): TestApplicationCall {
    return this.request(HttpMethod.Post, uri, setup)
}

fun TestApplicationEngine.put(uri: String, setup: TestApplicationRequest.() -> Unit): TestApplicationCall {
    return this.request(HttpMethod.Put, uri, setup)
}

fun TestApplicationEngine.patch(uri: String, setup: TestApplicationRequest.() -> Unit): TestApplicationCall {
    return this.request(HttpMethod.Patch, uri, setup)
}

fun TestApplicationEngine.delete(uri: String, setup: TestApplicationRequest.() -> Unit): TestApplicationCall {
    return this.request(HttpMethod.Delete, uri, setup)
}

open class KotestCommon() : StringSpec({
    beforeSpec {
        backend.start()
    }

    beforeEach {
        UpdateExecutionHTTP.service(backend.getUpdateUrl()).update("drop all").execute()

        RDFConnection.connect(backend.getGspdUrl()).use {
            it.putDataset("cluster.trig")
        }
    }

    afterSpec {
        backend.stop()
    }
})
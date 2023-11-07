package org.openmbee.mms5.util

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.testing.*
import org.openmbee.mms5.ROOT_CONTEXT
import java.util.*


data class AuthStruct (
    val username: String = "",
    val groups: List<String> = listOf("")
)

val rootAuth = AuthStruct("root")

val adminAuth = AuthStruct("admin", listOf("super_admins"))

val anonAuth = AuthStruct("anon")


fun localIri(suffix: String): String {
    return "$ROOT_CONTEXT$suffix"
}

fun userIri(user: String): String {
    return localIri("/users/$user")
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

fun TestApplicationRequest.setSparqlQueryBody(body: String) {
    addHeader("Content-Type", "application/sparql-query")
    setBody(body)
}

fun TestApplicationEngine.httpRequest(method: HttpMethod, uri: String, setup: TestApplicationRequest.() -> Unit): TestApplicationCall {
    if("1" != System.getProperty("FLEXO_MMS_TEST_NO_AUTH", "")) {
        handleRequest(method, uri) {
            addHeader("Authorization", authorization(anonAuth))
            setup()
        }.apply {
            if("" != System.getProperty("FLEXO_MMS_TEST_EXPECT", "")) {
                response shouldHaveStatus System.getProperty("FLEXO_MMS_TEST_EXPECT").toInt()
            }
            else {
                if(HttpStatusCode.OK === response.status()) {
                    println("?");
                }

                response shouldHaveOneOfStatuses setOf(HttpStatusCode.NotFound, HttpStatusCode.Forbidden)
            }

            // else if(method == HttpMethod.Get) {
            //
            // } else {
            //     response shouldHaveStatus HttpStatusCode.Forbidden
            // }
        }
    }

    return handleRequest(method, uri) {
        addHeader("Authorization", authorization(rootAuth))
        setup()
    }
}

fun TestApplicationEngine.httpHead(uri: String, setup: TestApplicationRequest.() -> Unit): TestApplicationCall {
    return this.httpRequest(HttpMethod.Head, uri, setup)
}

fun TestApplicationEngine.httpGet(uri: String, setup: TestApplicationRequest.() -> Unit): TestApplicationCall {
    return this.httpRequest(HttpMethod.Get, uri, setup)
}

fun TestApplicationEngine.httpPost(uri: String, setup: TestApplicationRequest.() -> Unit): TestApplicationCall {
    return this.httpRequest(HttpMethod.Post, uri, setup)
}

fun TestApplicationEngine.httpPut(uri: String, setup: TestApplicationRequest.() -> Unit): TestApplicationCall {
    return this.httpRequest(HttpMethod.Put, uri, setup)
}

fun TestApplicationEngine.httpPatch(uri: String, setup: TestApplicationRequest.() -> Unit): TestApplicationCall {
    return this.httpRequest(HttpMethod.Patch, uri, setup)
}

fun TestApplicationEngine.httpDelete(uri: String, setup: TestApplicationRequest.() -> Unit): TestApplicationCall {
    return this.httpRequest(HttpMethod.Delete, uri, setup)
}

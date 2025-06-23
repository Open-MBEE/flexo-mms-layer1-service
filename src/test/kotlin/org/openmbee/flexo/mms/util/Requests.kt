package org.openmbee.flexo.mms.util

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.kotest.assertions.ktor.client.shouldHaveHeader
import io.kotest.assertions.ktor.client.shouldHaveStatus
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.runner.Request.method
import org.openmbee.flexo.mms.ROOT_CONTEXT
import org.openmbee.flexo.mms.server.BuildInfo
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
fun HttpRequestBuilder.setTurtleBody(body: String) {
    header("Content-Type", "text/turtle")
    header("Content-Length", body.length.toString())
    setBody(body)
}

/**
 * Extension function to add a SPARQL update request body with appropriate Content-Type
 * to a test request
 */
fun HttpRequestBuilder.setSparqlUpdateBody(body: String) {
    header("Content-Type", "application/sparql-update")
    setBody(body)
}

fun HttpRequestBuilder.setSparqlQueryBody(body: String) {
    header("Content-Type", "application/sparql-query")
    setBody(body)
}

suspend fun ApplicationTestBuilder.httpRequest(method: HttpMethod, uri: String, skipAnon: Boolean = false, setup: HttpRequestBuilder.() -> Unit): HttpResponse {
    if (!skipAnon) {
        client.request {
            this.method = method
            this.url(uri)
            header("Authorization", authorization(anonAuth))
            setup()
        }.apply {
            if (HttpStatusCode.OK === this.status) {
                println("?");
            }
            this.status shouldBeIn setOf(
                HttpStatusCode.BadRequest,
                HttpStatusCode.Forbidden,
                HttpStatusCode.NotFound,
                HttpStatusCode.MethodNotAllowed,
                HttpStatusCode.NotImplemented,
            )
        }
    }
    return client.request {
        this.method = method
        this.url(uri)
        header("Authorization", authorization(rootAuth))
        setup()
    }.apply {
        this.shouldHaveHeader("Flexo-MMS-Layer-1", "Version=${BuildInfo.getProperty("build.version")}")
    }
}

suspend fun ApplicationTestBuilder.httpHead(uri: String, skipAnon: Boolean = false, setup: HttpRequestBuilder.() -> Unit): HttpResponse {
    return httpRequest(HttpMethod.Head, uri, skipAnon, setup)
}
suspend fun ApplicationTestBuilder.httpGet(uri:  String, skipAnon: Boolean = false, setup: HttpRequestBuilder.() -> Unit): HttpResponse {
    return httpRequest(HttpMethod.Get, uri, skipAnon, setup)
}
suspend fun ApplicationTestBuilder.httpPost(uri:  String, skipAnon: Boolean = false, setup: HttpRequestBuilder.() -> Unit): HttpResponse {
    return httpRequest(HttpMethod.Post, uri, skipAnon, setup)
}
suspend fun ApplicationTestBuilder.httpPut(uri:  String, skipAnon: Boolean = false, setup: HttpRequestBuilder.() -> Unit): HttpResponse {
    return httpRequest(HttpMethod.Put, uri, skipAnon, setup)
}
suspend fun ApplicationTestBuilder.httpPatch(uri:  String, skipAnon: Boolean = false, setup: HttpRequestBuilder.() -> Unit): HttpResponse {
    return httpRequest(HttpMethod.Patch, uri, skipAnon, setup)
}
suspend fun ApplicationTestBuilder.httpDelete(uri:  String, skipAnon: Boolean = false, setup: HttpRequestBuilder.() -> Unit): HttpResponse {
    return httpRequest(HttpMethod.Delete, uri, skipAnon, setup)
}

suspend fun ApplicationTestBuilder.onlyAllowsMethods(path: String, allowedMethods: Set<HttpMethod>) {
    val verbs: MutableMap<HttpMethod, suspend (path: String) -> HttpResponse> = mutableMapOf(
        HttpMethod.Head to { httpHead(it) {} },
        HttpMethod.Get to { httpGet(it) {} },
        HttpMethod.Post to { httpPost(it) {} },
        HttpMethod.Put to { httpPut(it) {} },
        HttpMethod.Patch to { httpPatch(it) {} },
        HttpMethod.Delete to { httpDelete(it) {} },
    )

    // remove each allowed method from the map
    for(allowedMethod in allowedMethods) {
        verbs.remove(allowedMethod)
    }

    // create expected HTTP header
    val expectedAllowHeader = "OPTIONS, ${allowedMethods.joinToString(", ") { it.value }}"

    // each remaining method
    for((method, verb) in verbs) {
        verb(path).apply {
            this shouldHaveStatus HttpStatusCode.MethodNotAllowed
            this.shouldHaveHeader("Allow", expectedAllowHeader)
            this.contentType().toString().shouldStartWith(ContentType.Text.Plain.toString())
        }
    }
}


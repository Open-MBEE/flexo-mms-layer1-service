package org.openmbee.mms5.util

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.testing.*
import junit.framework.TestCase.assertEquals
import org.openmbee.mms5.ROOT_CONTEXT
import java.util.*

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

fun ApplicationTestBuilder.httpRequest(method: HttpMethod, uri: String, setup: HttpRequestBuilder.() -> Unit): TestApplicationCall {
    if("1" != System.getProperty("MMS5_TEST_NO_AUTH", "")) {
        handleRequest(method, uri) {

            setup()
        }.apply {
            if("" != System.getProperty("MMS5_TEST_EXPECT", "")) {
                response shouldHaveStatus System.getProperty("MMS5_TEST_EXPECT").toInt()
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
        header("Authorization", authorization(rootAuth))
        setup()
    }
}

fun ApplicationTestBuilder.httpHead(uri: String, setup: HttpRequestBuilder.() -> Unit): HttpResponse {
    if("1" != System.getProperty("MMS5_TEST_NO_AUTH", "")) {

        testApplication {
            val feedback = client.head(uri) {
                header("Authorization", authorization(anonAuth))
                setup()
            }
            if ("" != System.getProperty("MMS5_TEST_EXPECT", "")) {
                assertEquals(feedback.status, System.getProperty("MMS5_TEST_EXPECT").toInt())
            } else {
                if (HttpStatusCode.OK === feedback.status) {
                    println("?");
                }
                val statusCodes = listOf(HttpStatusCode.NotFound, HttpStatusCode.Forbidden)
                assertEquals(true, statusCodes.contains(feedback.status))
            }
        }
    val feedbackReturn = client.head(uri){
            header("Authorization", authorization(rootAuth))
            setup()
    }

    return feedbackReturn
    //return this.httpRequest(HttpMethod.Head, uri, setup)
}

fun ApplicationTestBuilder.httpGet(uri: String, setup: HttpRequestBuilder.() -> Unit): TestApplicationCall {
    if("1" != System.getProperty("MMS5_TEST_NO_AUTH", "")) {
        val feedback = client.get(uri) {
            header("Authorization", authorization(anonAuth))
            setup()
        }
        if ("" != System.getProperty("MMS5_TEST_EXPECT", "")) {
            assertEquals(feedback.status, System.getProperty("MMS5_TEST_EXPECT").toInt())
        } else {
            if (HttpStatusCode.OK === feedback.status) {
                println("?");
            }
            val statusCodes = listOf(HttpStatusCode.NotFound, HttpStatusCode.Forbidden)
            assertEquals(true, statusCodes.contains(feedback.status))
        }
    }
    val feedbackReturn = client.get(uri) {
        header("Authorization", authorization(rootAuth))
        setup()
    }

    return feedbackReturn
}

fun ApplicationTestBuilder.httpPost(uri: String, setup: HttpRequestBuilder.() -> Unit): TestApplicationCall {
    if("1" != System.getProperty("MMS5_TEST_NO_AUTH", "")) {
        val feedback = client.post(uri){
            header("Authorization", authorization(anonAuth))
            setup()
        }
        if("" != System.getProperty("MMS5_TEST_EXPECT", "")) {
            assertEquals(feedback.status, System.getProperty("MMS5_TEST_EXPECT").toInt())
        }
        else {
            if(HttpStatusCode.OK === feedback.status) {
                println("?");
            }
            val statusCodes = listOf(HttpStatusCode.NotFound, HttpStatusCode.Forbidden)
            assertEquals(true, statusCodes.contains(feedback.status))
        }

        val feedbackReturn = client.post(uri){
            header("Authorization", authorization(rootAuth))
            setup()
        }

        return feedbackReturn
}

fun ApplicationTestBuilder.httpPut(uri: String, setup: HttpRequestBuilder.() -> Unit): TestApplicationCall {
    if("1" != System.getProperty("MMS5_TEST_NO_AUTH", "")) {
        val feedback = client.put(uri){
            header("Authorization", authorization(anonAuth))
            setup()
        }
        if("" != System.getProperty("MMS5_TEST_EXPECT", "")) {
            assertEquals(feedback.status, System.getProperty("MMS5_TEST_EXPECT").toInt())
        }
        else {
            if(HttpStatusCode.OK === feedback.status) {
                println("?");
            }
            val statusCodes = listOf(HttpStatusCode.NotFound, HttpStatusCode.Forbidden)
            assertEquals(true, statusCodes.contains(feedback.status))
        }

        val feedbackReturn = client.put(uri){
            header("Authorization", authorization(rootAuth))
            setup()
        }

        return feedbackReturn
}

fun ApplicationTestBuilder.httpPatch(uri: String, setup: HttpRequestBuilder.() -> Unit): TestApplicationCall {
    if("1" != System.getProperty("MMS5_TEST_NO_AUTH", "")) {
        val feedback = client.patch(uri){
            header("Authorization", authorization(anonAuth))
            setup()
        }
        if("" != System.getProperty("MMS5_TEST_EXPECT", "")) {
            assertEquals(feedback.status, System.getProperty("MMS5_TEST_EXPECT").toInt())
        }
        else {
            if(HttpStatusCode.OK === feedback.status) {
                println("?");
            }
            val statusCodes = listOf(HttpStatusCode.NotFound, HttpStatusCode.Forbidden)
            assertEquals(true, statusCodes.contains(feedback.status))
        }

        val feedbackReturn = client.patch(uri){
            header("Authorization", authorization(rootAuth))
            setup()
        }

        return feedbackReturn
}

fun ApplicationTestBuilder.httpDelete(uri: String, setup: HttpRequestBuilder.() -> Unit): TestApplicationCall {
    if("1" != System.getProperty("MMS5_TEST_NO_AUTH", "")) {
        val feedback = client.delete(uri){
            header("Authorization", authorization(anonAuth))
            setup()
        }
        if("" != System.getProperty("MMS5_TEST_EXPECT", "")) {
            assertEquals(feedback.status, System.getProperty("MMS5_TEST_EXPECT").toInt())
        }
        else {
            if(HttpStatusCode.OK === feedback.status) {
                println("?");
            }
            val statusCodes = listOf(HttpStatusCode.NotFound, HttpStatusCode.Forbidden)
            assertEquals(true, statusCodes.contains(feedback.status))
        }

        val feedbackReturn = client.delete(uri){
            header("Authorization", authorization(rootAuth))
            setup()
        }

        return feedbackReturn
}

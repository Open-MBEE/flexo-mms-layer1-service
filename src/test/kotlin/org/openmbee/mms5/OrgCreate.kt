package org.openmbee.mms5

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.kotest.assertions.fail


import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.sparql.vocabulary.FOAF
import org.openmbee.mms5.util.*
import java.util.*


class OrgCreate : OrgAny() {
    /*private fun authorization(auth: AuthStruct): String {
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
    }*/
    init {
        "reject invalid org id".config(tags=setOf(NoAuth)) {
        testApplication {
            val feedback = client.put("$orgPath with invalid id") {
               // header("Authorization", authorization(anonAuth))
                header("Content-Type", "text/turtle")
                setBody(validOrgBody)
            }
            assertEquals(feedback.status, HttpStatusCode.BadRequest)

        }
        /*withTest {
                httpPut("$orgPath with invalid id") {
                    setTurtleBody(validOrgBody)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.BadRequest
                }
            }*/
        }

        mapOf(
            "rdf:type" to "mms:NotOrg",
            "mms:id" to "\"not-$orgId\"",
            "mms:etag" to "\"${UUID.randomUUID()}\"",
        ).forEach { (pred, obj) ->
            "reject wrong $pred".config(tags=setOf(NoAuth)) {
                testApplication {
                    val feedback = client.put(orgPath){
                        header("Content-Type", "text/turtle")
                        setBody("""
                            $validOrgBody
                            <> $pred $obj .
                        """.trimIndent())
                    }
                    assertEquals(feedback.status, HttpStatusCode.BadRequest)
                    //need to find equivalent for checking TestApplicationResponse.shouldHaveStatus
                }
                /*withTest {
                    httpPut(orgPath) {
                        setTurtleBody("""
                            $validOrgBody
                            <> $pred $obj .
                        """.trimIndent())
                    }.apply {
                        response shouldHaveStatus HttpStatusCode.BadRequest
                    }
                }*/
            }
        }

        "create valid org" {
            testApplication {
                val feedback = client.put(orgPath){
                    header("Content-Type", "text/turtle")
                    setBody(validOrgBody)
                }
                assertEquals(feedback.status,HttpStatusCode.OK)
                assertNotNull(feedback.headers[HttpHeaders.ETag])
                assertEquals(feedback.headers[HttpHeaders.ContentType],"${RdfContentTypes.Turtle}; charset=UTF-8")
                val model = ModelFactory.createDefaultModel()
                parseTurtle(feedback.headers[HttpHeaders.ContentType].toString(), model, feedback.request.url.toString())
                //parseTurtle(this.content.toString(), model, this.call.request.uri)
            }
            /*withTest {
                httpPut(orgPath) {
                    setTurtleBody(validOrgBody)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response.headers[HttpHeaders.ETag].shouldNotBeBlank()

                    response exclusivelyHasTriples {
                        modelName = it

                        validateCreatedOrgTriples(response, orgId, orgName)
                    }
                }
            }*/
        }
    }
}
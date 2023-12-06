package org.openmbee.flexo.mms.util

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.http.*
import io.ktor.server.testing.*
import org.openmbee.flexo.mms.validateCreatedOrgTriples

class LinkedDataPlatformAsserter(val basePath: String) {
    var validBodyForCreate: String = ""
    var resourceId: String = "not-defined"
    var resourceName: String = ""

    fun TestApplicationCall.validateCreated(testName: String) {
        // LDP 4.2.1.3 - Etag
        response.headers[HttpHeaders.ETag].shouldNotBeBlank()

        // LDP 5.2.3.1 - Location header points to new resource
        response.headers[HttpHeaders.Location].shouldBe("$basePath/$resourceId")

        // LDP 5.2.3.1 - response with status code 201; body is not required by LDP
        response.exclusivelyHasTriples(HttpStatusCode.Created) {
            modelName = testName

            validateCreatedOrgTriples(response, resourceId, resourceName)
        }
    }

    fun CommonSpec.create() {
        basePath.let {
            "POST $it - reject invalid id".config(tags=setOf(NoAuth)) {
                withTest {
                    // POST
                    httpPost(basePath) {
                        // use Slug header to define new resource id
                        addHeader("Slug", "test with invalid id")

                        // set valid turtle body for creating new resource
                        setTurtleBody(withAllTestPrefixes(validBodyForCreate))
                    }.apply {
                        response shouldHaveStatus HttpStatusCode.BadRequest
                    }
                }
            }

            "POST $it - create valid" {
                withTest {
                    // POST
                    httpPost(basePath) {
                        // use Slug header to define new resource id
                         addHeader("Slug", resourceId)

                        // set valid turtle body for creating new resource
                        setTurtleBody(withAllTestPrefixes(validBodyForCreate))
                    }.apply {
                        validateCreated(it)
                    }
                }
            }

            "$it/$resourceId".let {
                val resourcePath = it

                "PUT $it - reject invalid".config(tags=setOf(NoAuth)) {
                    withTest {
                        // PUT
                        httpPut(resourcePath) {
                            // set valid turtle body for creating new resource
                            setTurtleBody(withAllTestPrefixes(validBodyForCreate))
                        }.apply {
                            response shouldHaveStatus HttpStatusCode.BadRequest
                        }
                    }
                }

                "PUT $it - create valid" {
                    withTest {
                        // PUT
                        httpPut(resourcePath) {
                            // set valid turtle body for creating new resource
                            setTurtleBody(withAllTestPrefixes(validBodyForCreate))
                        }.apply {
                           validateCreated(it)
                        }
                    }
                }

            }
        }


    }
}

fun CommonSpec.linkedDataPlatformDirectContainer(basePath: String, body: LinkedDataPlatformAsserter.() -> Unit) {
    val asserter = LinkedDataPlatformAsserter(basePath)

    body(asserter)
}
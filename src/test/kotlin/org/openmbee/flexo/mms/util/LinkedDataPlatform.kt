package org.openmbee.flexo.mms.util

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.http.*
import io.ktor.server.testing.*
import org.openmbee.flexo.mms.ROOT_CONTEXT

val EXHAUSTIVE_PRECONDITIONS = listOf(
    "*",
    "\"SOME_TAG\"",
    "\"SOME_TAG\", \"OTHER_TAG\"",
)

val CONFLICTING_PRECONDITIONS = listOf(
    mapOf(
        HttpHeaders.IfMatch to "*",
        HttpHeaders.IfNoneMatch to "*",
    ),
    mapOf(
        HttpHeaders.IfMatch to "\"SOME_TAG\"",
        HttpHeaders.IfNoneMatch to "*",
    ),
    mapOf(
        HttpHeaders.IfMatch to "\"SOME_TAG\"",
        HttpHeaders.IfNoneMatch to "\"SOME_TAG\"",
    ),
    mapOf(
        HttpHeaders.IfMatch to "\"SOME_TAG\", \"OTHER_TAG\"",
        HttpHeaders.IfNoneMatch to "\"SOME_TAG\", \"ANOTHER_TAG\"",
    ),
)

class LinkedDataPlatformDirectContainerTests(
    val basePath: String,
    var resourceId: String,
    var validBodyForCreate: String = "",
    body: LinkedDataPlatformDirectContainerTests.() -> Unit
) {

    init {
        body()
    }

    fun TestApplicationCall.validateCreatedLdpResource(testName: String, validator: (TriplesAsserter.(TestApplicationResponse) -> Unit)?=null) {
        // LDP 4.2.1.3 - Etag
        response.headers[HttpHeaders.ETag].shouldNotBeBlank()

        // LDP 5.2.3.1 - Location header points to new resource
        response.headers[HttpHeaders.Location].shouldBe("${ROOT_CONTEXT}$basePath/$resourceId")

        // LDP 5.2.3.1 - response with status code 201; body is not required by LDP
        response.exclusivelyHasTriples(HttpStatusCode.Created) {
            modelName = testName

            validator?.invoke(this, response)
        }
    }

    fun CommonSpec.create(validator: (TriplesAsserter.(TestApplicationResponse) -> Unit)?=null) {
        // POST to the resource container
        basePath.let {
            "POST $it - reject missing slug".config(tags = setOf(NoAuth)) {
                withTest {
                    httpPost(basePath) {
                        // set valid turtle body for creating new resource
                        setTurtleBody(withAllTestPrefixes(validBodyForCreate))
                    }.apply {
                        response shouldHaveStatus HttpStatusCode.BadRequest
                    }
                }
            }

            "POST $it - reject invalid id".config(tags = setOf(NoAuth)) {
                withTest {
                    httpPost(basePath) {
                        // use Slug header to define new resource id
                        addHeader(HttpHeaders.SLUG, "test with invalid id")

                        // set valid turtle body for creating new resource
                        setTurtleBody(withAllTestPrefixes(validBodyForCreate))
                    }.apply {
                        response shouldHaveStatus HttpStatusCode.BadRequest
                    }
                }
            }

            // conflicting preconditions
            CONFLICTING_PRECONDITIONS.forEachIndexed { index, preconditions ->
                "POST $it - reject conflicting preconditions #${index+1}".config(tags=setOf(NoAuth)) {
                    withTest {
                        httpPost(basePath) {
                            preconditions.forEach {
                                addHeader(it.key, it.value)
                            }

                            // use Slug header to define new resource id
                            addHeader(HttpHeaders.SLUG, resourceId)

                            // set valid turtle body for creating new resource
                            setTurtleBody(withAllTestPrefixes(validBodyForCreate))
                        }.apply {
                            response shouldHaveStatus HttpStatusCode.PreconditionFailed
                        }
                    }
                }
            }

            "POST $it - reject precondition: if-none-match star".config(tags = setOf(NoAuth)) {
                withTest {
                    httpPost(basePath) {
                        addHeader(HttpHeaders.IfNoneMatch, "*")

                        // use Slug header to define new resource id
                        addHeader(HttpHeaders.SLUG, resourceId)

                        // set valid turtle body for creating new resource
                        setTurtleBody(withAllTestPrefixes(validBodyForCreate))
                    }.apply {
                        response shouldHaveStatus HttpStatusCode.PreconditionFailed
                    }
                }
            }

            "POST $it - create valid" {
                withTest {
                    httpPost(basePath) {
                        // use Slug header to define new resource id
                        addHeader(HttpHeaders.SLUG, resourceId)

                        // set valid turtle body for creating new resource
                        setTurtleBody(withAllTestPrefixes(validBodyForCreate))
                    }.apply {
                        validateCreatedLdpResource(it, validator)
                    }
                }
            }
        }

        // PUT to a specific resource
        "$basePath/$resourceId".let {
            val resourcePath = it

            "PUT $it - reject invalid id".config(tags=setOf(NoAuth)) {
                withTest {
                    httpPut("$basePath/test with invalid id") {
                        // set valid turtle body for creating new resource
                        setTurtleBody(withAllTestPrefixes(validBodyForCreate))
                    }.apply {
                        response shouldHaveStatus HttpStatusCode.BadRequest
                    }
                }
            }

            EXHAUSTIVE_PRECONDITIONS.forEach { preconditions ->
                "PUT $it - reject failed precondition: if-match $preconditions".config(tags = setOf(NoAuth)) {
                    withTest {
                        httpPut(resourcePath) {
                            addHeader(HttpHeaders.IfMatch, preconditions)

                            // set valid turtle body for creating new resource
                            setTurtleBody(withAllTestPrefixes(validBodyForCreate))
                        }.apply {
                            response shouldHaveStatus HttpStatusCode.PreconditionFailed
                        }
                    }
                }
            }

            // conflicting preconditions
            CONFLICTING_PRECONDITIONS.forEachIndexed { index, preconditions ->
                "PUT $it - reject conflicting preconditions #${index+1}".config(tags=setOf(NoAuth)) {
                    withTest {
                        httpPut(resourcePath) {
                            preconditions.forEach {
                                addHeader(it.key, it.value)
                            }

                            // set valid turtle body for creating new resource
                            setTurtleBody(withAllTestPrefixes(validBodyForCreate))
                        }.apply {
                            response shouldHaveStatus HttpStatusCode.PreconditionFailed
                        }
                    }
                }
            }

            "PUT $it - create valid" {
                withTest {
                    httpPut(resourcePath) {
                        // set valid turtle body for creating new resource
                        setTurtleBody(withAllTestPrefixes(validBodyForCreate))
                    }.apply {
                       validateCreatedLdpResource(it, validator)
                    }
                }
            }


            EXHAUSTIVE_PRECONDITIONS.forEach { preconditions ->
                "PUT $it - create valid with precondition: if-none-match $preconditions" {
                    withTest {
                        httpPut(resourcePath) {
                            addHeader(HttpHeaders.IfNoneMatch, preconditions)

                            // set valid turtle body for creating new resource
                            setTurtleBody(withAllTestPrefixes(validBodyForCreate))
                        }.apply {
                           validateCreatedLdpResource(it, validator)
                        }
                    }
                }
            }
        }
    }

    fun CommonSpec.postWithPrecondition(
        validation: (TestApplicationCall.(testName: String) -> Unit)
    ) {
        "POST $basePath - with precondition: if-match star".config(tags = setOf(NoAuth)) {
            withTest {
                httpPost(basePath) {
                    addHeader(HttpHeaders.IfMatch, "*")

                    // use Slug header to define new resource id
                    addHeader(HttpHeaders.SLUG, resourceId)

                    // set valid turtle body for creating new resource
                    setTurtleBody(withAllTestPrefixes(validBodyForCreate))
                }.apply {
                    validation(it)
                }
            }
        }
    }
}

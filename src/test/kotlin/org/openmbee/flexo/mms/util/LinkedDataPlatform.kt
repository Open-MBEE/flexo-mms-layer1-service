package org.openmbee.flexo.mms.util

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.http.*
import io.ktor.server.testing.*
import org.apache.jena.sparql.vocabulary.FOAF
import org.openmbee.flexo.mms.ROOT_CONTEXT
import java.util.*

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

val PATCH_INSERT_TRIPLES = "<> foaf:homepage <https://www.openmbee.org/> ."

/**
 * Bundles responses for when resources get created in tests
 */
data class TestApplicationCreatedResponseBundle(
    val response: TestApplicationResponse,
    val createdBase: TestApplicationResponse,
    val createdOthers: List<TestApplicationResponse> = listOf(),
)

class LinkedDataPlatformDirectContainerTests(
    val basePath: String,
    var resourceId: String,
    var validBodyForCreate: String = "",
    body: LinkedDataPlatformDirectContainerTests.() -> Unit
) {
    val resourcePath = "$basePath/$resourceId"

    init {
        body()
    }

    fun TestApplicationCall.validateCreatedLdpResource(testName: String, validator: (TriplesAsserter.(TestApplicationResponse) -> Unit)?=null) {
        // LDP 4.2.1.3 - Etag
        response.headers[HttpHeaders.ETag].shouldNotBeBlank()

        // LDP 5.2.3.1 - Location header points to new resource
        response.headers[HttpHeaders.Location].shouldBe("${ROOT_CONTEXT}$resourcePath")

        // LDP 5.2.3.1 - response with status code 201; body is not required by LDP
        response.exclusivelyHasTriples(HttpStatusCode.Created) {
            modelName = testName

            validator?.invoke(this, response)
        }
    }

    /**
     * Checks that the LDP direct container responds correctly to various create calls
     */
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
        run {
            "PUT $resourcePath - reject invalid id".config(tags = setOf(NoAuth)) {
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
                "PUT $resourcePath - reject failed precondition: if-match $preconditions".config(tags = setOf(NoAuth)) {
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
                "PUT $resourcePath - reject conflicting preconditions #${index + 1}".config(tags = setOf(NoAuth)) {
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

            "PUT $resourcePath - create valid" {
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
                "PUT $resourcePath - create valid with precondition: if-none-match $preconditions" {
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

    /**
     * Checks that the LDP direct container responds correctly to POST'ing with precondition
     */
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

    /**
     * Checks that the LDP direct container responds correctly to various read calls
     */
    fun CommonSpec.read(
        creator: () -> TestApplicationCall,
        vararg creators: () -> TestApplicationCall,
        validator: ((TestApplicationCreatedResponseBundle) -> Unit)
    ) {
        "HEAD $resourcePath - non-existent" {
            withTest {
                httpHead(resourcePath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }

        "GET $resourcePath - non-existent" {
            withTest {
                httpGet(resourcePath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }

        "HEAD $resourcePath - valid" {
            val createdBase = creator()
            val etag = createdBase.response.headers[HttpHeaders.ETag]!!

            withTest {
                httpHead(resourcePath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.NoContent
                    response.shouldHaveHeader(HttpHeaders.ETag, etag)
                }
            }
        }

        "GET $resourcePath - valid" {
            val createdBase = creator()
            val etag = createdBase.response.headers[HttpHeaders.ETag]!!

            withTest {
                httpGet(resourcePath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response.shouldHaveHeader(HttpHeaders.ETag, etag)

                    validator(TestApplicationCreatedResponseBundle(response, createdBase.response))
                }
            }
        }

        "GET $resourcePath - if-match etag" {
            val createdBase = creator()
            val etag = createdBase.response.headers[HttpHeaders.ETag]!!

            withTest {
                httpGet(resourcePath) {
                    addHeader(HttpHeaders.IfMatch, "\"$etag\"")
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                }
            }
        }

        "GET $resourcePath - if-match random" {
            creator()

            withTest {
                httpGet(resourcePath) {
                    addHeader(HttpHeaders.IfMatch, "\"${UUID.randomUUID()}\"")
                }.apply {
                    response shouldHaveStatus HttpStatusCode.PreconditionFailed
                }
            }
        }

        "GET $resourcePath - if-none-match etag" {
            val createdBase = creator()
            val etag = createdBase.response.headers[HttpHeaders.ETag]!!

            withTest {
                httpGet(resourcePath) {
                    addHeader(HttpHeaders.IfNoneMatch, "\"$etag\"")
                }.apply {
                    response shouldHaveStatus HttpStatusCode.NotModified
                }
            }
        }

        "GET $resourcePath - if-none-match star" {
            creator()

            withTest {
                httpGet(resourcePath) {
                    addHeader(HttpHeaders.IfNoneMatch, "*")
                }.apply {
                    response shouldHaveStatus HttpStatusCode.NotModified
                }
            }
        }

        "GET $basePath - all resources" {
            val createdBase = creator()
            val createdOthers = creators.map { it() }

            withTest {
                httpGet(basePath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK

                    response.includesTriples {
                        modelName = it

                        validator(TestApplicationCreatedResponseBundle(response, createdBase.response, createdOthers.map { it.response }))
                    }
                }
            }
        }
    }


    /**
     * Checks that the LDP direct container responds correctly to various patch calls
     */
    fun CommonSpec.patch(
        creator: () -> TestApplicationCall,
    ) {
        fun validatePatchResponse(response: TestApplicationResponse) {
            response shouldHaveStatus HttpStatusCode.OK

            response includesTriples {
                subject(localIri(resourcePath)) {
                    includes(
                        FOAF.homepage exactly model.createResource("https://www.openmbee.org/")
                    )
                }
            }
        }

        "PATCH $resourcePath - Turtle: insert 1 triple unconditionally" {
            val createdBase = creator()

            withTest {
                httpPatch(resourcePath) {
                    setTurtleBody(withAllTestPrefixes("""
                        $PATCH_INSERT_TRIPLES
                    """.trimIndent()))
                }.apply {
                    validatePatchResponse(response)
                }
            }
        }

        "PATCH $resourcePath - SPARQL UPDATE: insert 1 triple unconditionally" {
            val createdBase = creator()

            withTest {
                httpPatch(resourcePath) {
                    setSparqlUpdateBody(withAllTestPrefixes("""
                        insert data {
                            $PATCH_INSERT_TRIPLES
                        }
                    """.trimIndent()))
                }.apply {
                    validatePatchResponse(response)
                }
            }
        }

        "PATCH $resourcePath - SPARQL UPDATE: insert 1 triple conditionally passing" {
            val createdBase = creator()

            withTest {
                httpPatch(resourcePath) {
                    setSparqlUpdateBody(withAllTestPrefixes("""
                        insert {
                            $PATCH_INSERT_TRIPLES
                        }
                        where {
                            <> ?p ?o .
                        }
                    """.trimIndent()))
                }.apply {
                    validatePatchResponse(response)
                }
            }
        }

        "PATCH $resourcePath - SPARQL UPDATE: insert 1 triple conditionally failing" {
            val createdBase = creator()

            withTest {
                httpPatch(resourcePath) {
                    setSparqlUpdateBody(withAllTestPrefixes("""
                        insert {
                            $PATCH_INSERT_TRIPLES
                        }
                        where {
                            <> <urn:mms:never> <urn:mms:never> .
                        }
                    """.trimIndent()))
                }.apply {
                    response shouldHaveStatus HttpStatusCode.PreconditionFailed
                }
            }
        }
    }
}

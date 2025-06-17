package org.openmbee.flexo.mms.util

import io.kotest.assertions.ktor.client.shouldHaveHeader
import io.kotest.assertions.ktor.client.shouldHaveStatus
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.apache.jena.sparql.vocabulary.FOAF
import org.openmbee.flexo.mms.ROOT_CONTEXT
import java.net.URLEncoder
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
    val response: HttpResponse,
    val createdBase: HttpResponse,
    val createdOthers: List<HttpResponse> = listOf(),
)

class LinkedDataPlatformDirectContainerTests(
    val basePath: String,
    var resourceId: String,
    var validBodyForCreate: String = "",
    val resourceCreator: suspend ApplicationTestBuilder.() -> HttpResponse,
    val useCreatorLocationForResource: Boolean = false,
    body: LinkedDataPlatformDirectContainerTests.() -> Unit
) {
    var resourcePath = "$basePath/${URLEncoder.encode(resourceId, "UTF-8")}"

    fun setResource(call: HttpResponse) {
        if (useCreatorLocationForResource) {
            resourceId = call.headers[HttpHeaders.Location]!!.removePrefix("$ROOT_CONTEXT$basePath/")
            resourcePath = "$basePath/${URLEncoder.encode(resourceId, "UTF-8")}"
        }
    }

    init {
        body()
    }

    // overloaded version of below
    suspend fun HttpResponse.validateCreatedLdpResource(
        testName: String,
        validator: (TriplesAsserter.(HttpResponse, String) -> Unit)?=null,
    ) {
        validateCreatedLdpResource(testName, validator, false)
    }

    suspend fun HttpResponse.validateCreatedLdpResource(
        testName: String,
        validator: (TriplesAsserter.(HttpResponse, String) -> Unit)?=null,
        slugWasOmitted: Boolean?=false
    ) {
        // LDP 4.2.1.3 - Etag
        withClue("Expected created LDP resource to return ETag header") {
            this.headers[HttpHeaders.ETag].shouldNotBeBlank()
        }

        // LDP 5.2.3.1 - Location header points to new resource
        val location = this.headers[HttpHeaders.Location]
        withClue("Expected created LDP resource to return Location header") {
            location.shouldNotBeBlank()
        }

        // prepare to extract the slug
        var slug = ""

        // slug was omitted from call
        if(slugWasOmitted == true) {
            // extract slug from location
            "/([^/]+)$".toRegex().find(location!!)?.let {
                slug = it.groupValues[1]
            } ?: assert(false) { "Failed to match slug" }
        }
        // slug was provided
        else {
            // expect location to match resource path
            location.shouldBe("${ROOT_CONTEXT}$resourcePath")

            // set slug from resource id
            slug = resourceId
        }

        // LDP 5.2.3.1 - response with status code 201; body is not required by LDP
        this shouldHaveStatus HttpStatusCode.Created
        this exclusivelyHasTriples {
            modelName = testName

            validator?.invoke(this, this@validateCreatedLdpResource, slug)
        }
    }

    /**
     * Checks that the LDP direct container responds correctly to various create calls
     */
    fun CommonSpec.create(validator: (TriplesAsserter.(HttpResponse, String) -> Unit)?=null) {
        // POST to the resource container
        basePath.let {
            "POST $it - allow missing slug".config(tags = setOf(NoAuth)) {
                testApplication {
                    httpPost(basePath) {
                        // set valid turtle body for creating new resource
                        setTurtleBody(withAllTestPrefixes(validBodyForCreate))
                    }.apply {
                        validateCreatedLdpResource(it, validator, true)
                    }
                }
            }

            "POST $it - reject invalid id".config(tags = setOf(NoAuth)) {
                testApplication {
                    httpPost(basePath) {
                        // use Slug header to define new resource id
                        header(HttpHeaders.SLUG, "test with invalid id")

                        // set valid turtle body for creating new resource
                        setTurtleBody(withAllTestPrefixes(validBodyForCreate))
                    }.apply {
                        this shouldHaveStatus HttpStatusCode.BadRequest
                    }
                }
            }

            // conflicting preconditions
            CONFLICTING_PRECONDITIONS.forEachIndexed { index, preconditions ->
                "POST $it - reject conflicting preconditions #${index+1}".config(tags=setOf(NoAuth)) {
                    testApplication {
                        httpPost(basePath) {
                            preconditions.forEach {
                                header(it.key, it.value)
                            }

                            // use Slug header to define new resource id
                            header(HttpHeaders.SLUG, resourceId)

                            // set valid turtle body for creating new resource
                            setTurtleBody(withAllTestPrefixes(validBodyForCreate))
                        }.apply {
                            this shouldHaveStatus HttpStatusCode.PreconditionFailed
                        }
                    }
                }
            }

            "POST $it - reject precondition: if-none-match star".config(tags = setOf(NoAuth)) {
                testApplication {
                    httpPost(basePath) {
                        header(HttpHeaders.IfNoneMatch, "*")

                        // use Slug header to define new resource id
                        header(HttpHeaders.SLUG, resourceId)

                        // set valid turtle body for creating new resource
                        setTurtleBody(withAllTestPrefixes(validBodyForCreate))
                    }.apply {
                        this shouldHaveStatus HttpStatusCode.PreconditionFailed
                    }
                }
            }

            "POST $it - create valid" {
                testApplication {
                    httpPost(basePath) {
                        // use Slug header to define new resource id
                        header(HttpHeaders.SLUG, resourceId)

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
                testApplication {
                    httpPut("$basePath/test with invalid id") {
                        // set valid turtle body for creating new resource
                        setTurtleBody(withAllTestPrefixes(validBodyForCreate))
                    }.apply {
                        this shouldHaveStatus HttpStatusCode.BadRequest
                    }
                }
            }

            EXHAUSTIVE_PRECONDITIONS.forEach { preconditions ->
                "PUT $resourcePath - reject failed precondition: if-match $preconditions".config(tags = setOf(NoAuth)) {
                    testApplication {
                        httpPut(resourcePath) {
                            header(HttpHeaders.IfMatch, preconditions)

                            // set valid turtle body for creating new resource
                            setTurtleBody(withAllTestPrefixes(validBodyForCreate))
                        }.apply {
                            this shouldHaveStatus HttpStatusCode.PreconditionFailed
                        }
                    }
                }
            }

            // conflicting preconditions
            CONFLICTING_PRECONDITIONS.forEachIndexed { index, preconditions ->
                "PUT $resourcePath - reject conflicting preconditions #${index + 1}".config(tags = setOf(NoAuth)) {
                    testApplication {
                        httpPut(resourcePath) {
                            preconditions.forEach {
                                header(it.key, it.value)
                            }

                            // set valid turtle body for creating new resource
                            setTurtleBody(withAllTestPrefixes(validBodyForCreate))
                        }.apply {
                            this shouldHaveStatus HttpStatusCode.PreconditionFailed
                        }
                    }
                }
            }

            "PUT $resourcePath - create valid" {
                testApplication {
                    httpPut(resourcePath) {
                        // set valid turtle body for creating new resource
                        setTurtleBody(withAllTestPrefixes(validBodyForCreate))
                    }.apply {
                        validateCreatedLdpResource("PUT $resourcePath - create valid", validator)
                    }
                }
            }


            EXHAUSTIVE_PRECONDITIONS.forEach { preconditions ->
                "PUT $resourcePath - create valid with precondition: if-none-match $preconditions" {
                    testApplication {
                        httpPut(resourcePath) {
                            header(HttpHeaders.IfNoneMatch, preconditions)

                            // set valid turtle body for creating new resource
                            setTurtleBody(withAllTestPrefixes(validBodyForCreate))
                        }.apply {
                            validateCreatedLdpResource("PUT $resourcePath - create valid with precondition: if-none-match $preconditions", validator)
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
        validation: suspend (HttpResponse.(testName: String) -> Unit)
    ) {
        "POST $basePath - with precondition: if-match star".config(tags = setOf(NoAuth)) {
            testApplication {
                httpPost(basePath) {
                    header(HttpHeaders.IfMatch, "*")

                    // use Slug header to define new resource id
                    header(HttpHeaders.SLUG, resourceId)

                    // set valid turtle body for creating new resource
                    setTurtleBody(withAllTestPrefixes(validBodyForCreate))
                }.apply {
                    validation("POST $basePath - with precondition: if-match star")
                }
            }
        }
    }

    /**
     * Checks that the LDP direct container responds correctly to various read calls
     */
    fun CommonSpec.read(
        vararg creators: suspend ApplicationTestBuilder.() -> HttpResponse,
        validator: suspend ((TestApplicationCreatedResponseBundle) -> Unit)
    ) {
        "HEAD $basePath - empty" {
            testApplication {
                httpHead(basePath) {}.apply {
                    this shouldHaveStatus HttpStatusCode.NoContent
                    this.headers["ETag"].shouldNotBeBlank()
                }
            }
        }

        "GET $basePath - empty" {
            testApplication {
                httpGet(basePath) {}.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this.headers["ETag"].shouldNotBeBlank()
                }
            }
        }

        "HEAD $resourcePath - non-existent" {
            testApplication {
                httpHead(resourcePath) {}.apply {
                    this shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }

        "GET $resourcePath - non-existent" {
            testApplication {
                httpGet(resourcePath) {}.apply {
                    this shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }

        "HEAD $resourcePath - valid" {
            testApplication {
                val createdBase = resourceCreator()
                val etag = createdBase.headers[HttpHeaders.ETag]!!
                setResource(createdBase)
                httpHead(resourcePath) {}.apply {
                    this shouldHaveStatus HttpStatusCode.NoContent
                    this.shouldHaveHeader(HttpHeaders.ETag, etag)
                }
            }
        }

        "GET $resourcePath - valid" {
            testApplication {
                val createdBase = resourceCreator()
                val etag = createdBase.headers[HttpHeaders.ETag]!!
                setResource(createdBase)
                httpGet(resourcePath) {}.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this.shouldHaveHeader(HttpHeaders.ETag, etag)

                    validator(TestApplicationCreatedResponseBundle(this, createdBase))
                }
            }
        }

        "GET $resourcePath - if-match etag" {
            testApplication {
                val createdBase = resourceCreator()
                val etag = createdBase.headers[HttpHeaders.ETag]!!
                setResource(createdBase)
                httpGet(resourcePath) {
                    header(HttpHeaders.IfMatch, "\"$etag\"")
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                }
            }
        }

        "GET $resourcePath - if-match random" {
            testApplication {
                val createdBase = resourceCreator()
                setResource(createdBase)
                httpGet(resourcePath) {
                    header(HttpHeaders.IfMatch, "\"${UUID.randomUUID()}\"")
                }.apply {
                    this shouldHaveStatus HttpStatusCode.PreconditionFailed
                }
            }
        }

        "GET $resourcePath - if-none-match etag" {
            testApplication {
                val createdBase = resourceCreator()
                val etag = createdBase.headers[HttpHeaders.ETag]!!
                setResource(createdBase)
                httpGet(resourcePath) {
                    header(HttpHeaders.IfNoneMatch, "\"$etag\"")
                }.apply {
                    this shouldHaveStatus HttpStatusCode.NotModified
                }
            }
        }

        "GET $resourcePath - if-none-match star" {
            testApplication {
                val createdBase = resourceCreator()
                setResource(createdBase)
                httpGet(resourcePath) {
                    header(HttpHeaders.IfNoneMatch, "*")
                }.apply {
                    this shouldHaveStatus HttpStatusCode.NotModified
                }
            }
        }

        "GET $basePath - all resources" {
            testApplication {
                val createdBase = resourceCreator()
                val createdOthers = creators.map { it() }
                setResource(createdBase)
                httpGet(basePath) {}.apply {
                    this shouldHaveStatus HttpStatusCode.OK

                    this.includesTriples {
                        modelName = basePath

                       //TODO validator(TestApplicationCreatedResponseBundle(this@apply, createdBase, createdOthers))
                    }
                }
            }
        }
    }

    fun CommonSpec.replaceExisting(validator: suspend ((HttpResponse) -> Unit)) {
        "PUT $resourcePath - replace resource" {
            testApplication {
                resourceCreator()
                httpPut(resourcePath) {
                    setTurtleBody(validBodyForCreate)
                }.apply {
                    validator(this)
                }
            }
        }
    }

    /**
     * Checks that the LDP direct container responds correctly to various patch calls
     */
    fun CommonSpec.patch() {
        suspend fun validatePatchResponse(response: HttpResponse) {
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
            testApplication {
                val createdBase = resourceCreator()
                setResource(createdBase)
                httpPatch(resourcePath) {
                    setTurtleBody(withAllTestPrefixes("""
                        $PATCH_INSERT_TRIPLES
                    """.trimIndent()))
                }.apply {
                    validatePatchResponse(this)
                }
            }
        }

        "PATCH $resourcePath - SPARQL UPDATE: insert 1 triple unconditionally" {
            testApplication {
                val createdBase = resourceCreator()
                setResource(createdBase)
                httpPatch(resourcePath) {
                    setSparqlUpdateBody(withAllTestPrefixes("""
                        insert data {
                            $PATCH_INSERT_TRIPLES
                        }
                    """.trimIndent()))
                }.apply {
                    validatePatchResponse(this)
                }
            }
        }

        "PATCH $resourcePath - SPARQL UPDATE: insert 1 triple conditionally passing" {
            testApplication {
                val createdBase = resourceCreator()
                setResource(createdBase)
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
                    validatePatchResponse(this)
                }
            }
        }

        "PATCH $resourcePath - SPARQL UPDATE: insert 1 triple conditionally failing" {
            testApplication {
                val createdBase = resourceCreator()
                setResource(createdBase)
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
                    this shouldHaveStatus HttpStatusCode.PreconditionFailed
                }
            }
        }

        "PATCH $resourcePath - SPARQL UPDATE: patch branch with bad delete data" {
            testApplication {
                val createdBase = resourceCreator()     // This creates a tuple
                setResource(createdBase)
                httpPatch(resourcePath) {
                    setSparqlUpdateBody(
                        withAllTestPrefixes("""
                            delete data {
                                <> mms:id <urn:mms:foo> .
                            }
                        """.trimIndent())
                    )
                }.apply {
                    this shouldHaveStatus HttpStatusCode.BadRequest
                }
            }
        }

        "PATCH $resourcePath - SPARQL UPDATE: patch branch with bad insert data" {
            testApplication {
                val createdBase = resourceCreator()
                setResource(createdBase)
                httpPatch(resourcePath) {
                    setSparqlUpdateBody(withAllTestPrefixes("""
                        insert data {
                            <> mms:id <urn:mms:foo> .
                        }
                    """.trimIndent()))
                }.apply {
                    this shouldHaveStatus HttpStatusCode.BadRequest
                }
            }
        }

        "PATCH $resourcePath - SPARQL UPDATE: patch branch with bad delete pattern" {
            testApplication {
                val createdBase = resourceCreator()     // This creates a tuple
                setResource(createdBase)
                httpPatch(resourcePath) {
                    setSparqlUpdateBody(
                        withAllTestPrefixes("""
                            delete {
                                <> mms:id <urn:mms:foo> .
                            }
                            where {
                                ?s ?p ?o .
                            }
                        """.trimIndent())
                    )
                }.apply {
                    this shouldHaveStatus HttpStatusCode.BadRequest
                }
            }
        }

        "PATCH $resourcePath - SPARQL UPDATE: patch branch with bad insert pattern" {
            testApplication {
                val createdBase = resourceCreator()     // This creates a tuple
                setResource(createdBase)
                httpPatch(resourcePath) {
                    setSparqlUpdateBody(
                        withAllTestPrefixes("""
                            insert {
                                <> mms:id <urn:mms:foo> .
                            }
                            where {
                                ?s ?p ?o .
                            }
                        """.trimIndent())
                    )
                }.apply {
                    this shouldHaveStatus HttpStatusCode.BadRequest
                }
            }
        }

        "PATCH $resourcePath - SPARQL UPDATE: patch branch with bad delete predicate variable" {
            testApplication {
                val createdBase = resourceCreator()     // This creates a tuple
                setResource(createdBase)
                httpPatch(resourcePath) {
                    setSparqlUpdateBody(
                        withAllTestPrefixes("""
                            delete {
                                <> ?p <urn:mms:foo> .
                            }
                            where {
                                ?s ?p ?o .
                            }
                        """.trimIndent())
                    )
                }.apply {
                    this shouldHaveStatus HttpStatusCode.BadRequest
                }
            }
        }
    }


    /**
     * Checks that the LDP direct container responds correctly to various delete calls
     */
    fun CommonSpec.delete() {
        "DELETE $resourcePath" {
            testApplication {
                val createdBase = resourceCreator()
                // delete resource should work
                httpDelete(resourcePath) {}.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                }

                // get deleted resource should 404
                httpGet(resourcePath) {}.apply {
                    this shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }
    }
}

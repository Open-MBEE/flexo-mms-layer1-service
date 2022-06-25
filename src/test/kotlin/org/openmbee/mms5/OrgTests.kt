package org.openmbee.mms5

import io.kotest.assertions.ktor.shouldHaveHeader
import io.kotest.assertions.ktor.shouldHaveStatus
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.http.*
import io.ktor.server.testing.*
import org.apache.jena.sparql.vocabulary.FOAF
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.mms5.util.*
import java.util.*

fun TriplesAsserter.validateOrgTriples(
    createResponse: TestApplicationResponse,
    orgId: String,
    orgName: String,
    extraPatterns: List<PairPattern> = listOf()
) {
    val orgPath = localIri(orgId)

    subject(localIri(orgPath)) {
        exclusivelyHas(
            RDF.type exactly MMS.Org,
            MMS.id exactly orgId,
            DCTerms.title exactly orgName.en,
            MMS.etag exactly createResponse.headers[HttpHeaders.ETag]!!,
            *extraPatterns.toTypedArray()
        )
    }
}

class OrgTests : CommonSpec() {
    val orgId = "open-mbee"
    val orgName = "OpenMBEE"
    val orgPath = "/orgs/$orgId"

    val orgFooId = "foo-org"
    val orgFooName = "Foo Org"
    val orgFooPath = "/orgs/$orgFooId"

    val orgBarId = "bar-org"
    val orgBarName = "Bar Org"
    val orgBarPath = "/orgs/$orgBarId"

    val validOrgBody = """
        <> dct:title "$orgName"@en .
    """.trimIndent()

    init {
        "head non-existent org" {
            withTest {
                httpHead(orgPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }

        "get non-existent org" {
            withTest {
                httpGet(orgPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }

        "reject invalid org id" {
            withTest {
                httpPut("/orgs/invalid org id") {
                    setTurtleBody(validOrgBody)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.BadRequest
                }
            }
        }

        "create valid org" {
            withTest {
                httpPut(orgPath) {
                    setTurtleBody(validOrgBody)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response.headers[HttpHeaders.ETag].shouldNotBeBlank()

                    response exclusivelyHasTriples {
                        modelName = it

                        validateOrgTriples(response, orgId, orgName)
                    }
                }
            }
        }

        "head org" {
            val create = createOrg(orgId, orgName)

            withTest {
                httpHead(orgPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response.shouldHaveHeader(HttpHeaders.ETag, create.response.headers[HttpHeaders.ETag]!!)
                }
            }
        }

        "get org" {
            val create = createOrg(orgId, orgName)

            withTest {
                httpGet(orgPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response.shouldHaveHeader(HttpHeaders.ETag, create.response.headers[HttpHeaders.ETag]!!)

                    response exclusivelyHasTriples {
                        validateOrgTriples(response, orgId, orgName)
                    }
                }
            }
        }

        "get org if-match etag" {
            withTest {
                val etag = createOrg(orgId, orgName).response.headers[HttpHeaders.ETag]

                httpGet(orgPath) {
                    addHeader("If-Match", "\"${etag}\"")
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                }
            }
        }

        "get org if-match random" {
            withTest {
                createOrg(orgId, orgName).response.headers[HttpHeaders.ETag]

                httpGet(orgPath) {
                    addHeader("If-Match", "\"${UUID.randomUUID()}\"")
                }.apply {
                    response shouldHaveStatus HttpStatusCode.PreconditionFailed
                }
            }
        }

        "get org if-none-match etag" {
            val create = createOrg(orgId, orgName)

            withTest {
                httpGet(orgPath) {
                    addHeader("If-None-Match", create.response.headers[HttpHeaders.ETag]!!)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.PreconditionFailed
                }
            }
        }

        "get org if-none-match star" {
            createOrg(orgId, orgName)

            withTest {
                httpGet(orgPath) {
                    addHeader("If-None-Match", "*")
                }.apply {
                    response shouldHaveStatus HttpStatusCode.NotModified
                }
            }
        }

        "patch org insert" {
            createOrg(orgId, orgName)

            withTest {
                httpPatch(orgPath) {
                    setSparqlUpdateBody("""
                        insert {
                            <> foaf:homepage <https://www.openmbee.org/> .
                        }
                        where {
                            <> dct:title "$orgName"@en .
                        }
                    """.trimIndent())
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK

                    response exclusivelyHasTriples {
                        validateOrgTriples(response, orgId, orgName, listOf(
                            FOAF.homepage exactly "https://www.openmbee.org/".iri
                        ))
                    }
                }
            }
        }

        "patch org insert failed condition" {
            createOrg(orgId, orgName)

            withTest {
                httpPatch(orgPath) {
                    setSparqlUpdateBody("""
                        insert {
                            <> foaf:homepage <https://www.openmbee.org/> .
                        }
                        where {
                            <> dct:title "Not $orgName"@en .
                        }
                    """.trimIndent())
                }.apply {
                    response shouldHaveStatus HttpStatusCode.PreconditionFailed
                }
            }
        }

        "delete org" {
            createOrg(orgId, orgName)

            withTest {
                // delete org should work
                httpDelete(orgPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                }

                // get deleted org should 404
                httpGet(orgPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }

        "get all orgs" {
            val createBase = createOrg(orgId, orgName)
            val createFoo = createOrg(orgFooId, orgFooName)
            val createBar = createOrg(orgBarId, orgBarName)

            withTest {
                httpGet("/orgs") {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK

                    response.exclusivelyHasTriples {
                        modelName = it

                        subject(localIri(orgPath)) {
                            validateOrgTriples(createBase.response, orgId, orgName)
                            validateOrgTriples(createFoo.response, orgFooId, orgFooName)
                            validateOrgTriples(createBar.response, orgBarId, orgBarName)
                        }
                    }
                }
            }
        }
    }
}
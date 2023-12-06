package org.openmbee.flexo.mms


import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.http.*
import org.openmbee.flexo.mms.util.*
import java.util.*


class OrgCreate : OrgAny() {
    init {
        linkedDataPlatformDirectContainer("/orgs") {
            validBodyForCreate = validOrgBody
            resourceId = orgId
            resourceName = orgName

            create()
        }

        "reject invalid org id via POST".config(tags=setOf(NoAuth)) {
            withTest {
                httpPost(orgsPath) {
                    addHeader("Slug", "$orgId with invalid id")

                    setTurtleBody(withAllTestPrefixes(validOrgBody))
                }.apply {
                    response shouldHaveStatus HttpStatusCode.BadRequest
                }
            }
        }

        "reject invalid org id via PUT".config(tags=setOf(NoAuth)) {
            withTest {
                httpPut("$orgPath with invalid id") {
                    setTurtleBody(withAllTestPrefixes(validOrgBody))
                }.apply {
                    response shouldHaveStatus HttpStatusCode.BadRequest
                }
            }
        }

        mapOf(
            "rdf:type" to "mms:NotOrg",
            "mms:id" to "\"not-$orgId\"",
            "mms:etag" to "\"${UUID.randomUUID()}\"",
        ).forEach { (pred, obj) ->
            "reject wrong $pred".config(tags=setOf(NoAuth)) {
                withTest {
                    httpPut(orgPath) {
                        setTurtleBody(withAllTestPrefixes("""
                            $validOrgBody
                            <> $pred $obj .
                        """.trimIndent()))
                    }.apply {
                        response shouldHaveStatus HttpStatusCode.BadRequest
                    }
                }
            }
        }

        "create valid org via POST" {
            withTest {
                httpPost(orgsPath) {
                     addHeader("Slug", orgId)

                    setTurtleBody(withAllTestPrefixes(validOrgBody))
                }.apply {
                    response.headers[HttpHeaders.ETag].shouldNotBeBlank()

                    // LDP 5.2.3.1
                    response.headers[HttpHeaders.Location].shouldBe(orgPath)

                    response.exclusivelyHasTriples(HttpStatusCode.Created) {
                        modelName = it

                        validateCreatedOrgTriples(response, orgId, orgName)
                    }
                }
            }
        }

        "create valid org via PUT" {
            withTest {
                httpPut(orgPath) {
                    setTurtleBody(withAllTestPrefixes(validOrgBody))
                }.apply {
                    response.headers[HttpHeaders.ETag].shouldNotBeBlank()

                    response.exclusivelyHasTriples(HttpStatusCode.Created) {
                        modelName = it

                        validateCreatedOrgTriples(response, orgId, orgName)
                    }
                }
            }
        }
    }
}
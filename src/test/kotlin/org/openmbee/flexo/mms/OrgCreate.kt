package org.openmbee.flexo.mms

import io.kotest.assertions.fail


import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.http.*
import org.apache.jena.sparql.vocabulary.FOAF
import org.openmbee.flexo.mms.util.*
import java.util.*


class OrgCreate : OrgAny() {
    init {
        "reject invalid org id".config(tags=setOf(NoAuth)) {
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

        "create valid org" {
            withTest {
                httpPut(orgPath) {
                    setTurtleBody(withAllTestPrefixes(validOrgBody))
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response.headers[HttpHeaders.ETag].shouldNotBeBlank()

                    response exclusivelyHasTriples {
                        modelName = it

                        validateCreatedOrgTriples(response, orgId, orgName)
                    }
                }
            }
        }
    }
}
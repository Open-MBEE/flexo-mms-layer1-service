package org.openmbee.mms5

import io.kotest.assertions.fail
import io.kotest.assertions.ktor.shouldHaveHeader
import io.kotest.assertions.ktor.shouldHaveStatus
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.http.*
import org.apache.jena.sparql.vocabulary.FOAF
import org.openmbee.mms5.util.*
import java.util.*

class OrgCreate : OrgAny() {
    init {
        "reject invalid org id" {
            withTest {
                httpPut("$orgPath with invalid id") {
                    setTurtleBody(validOrgBody)
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
            "reject wrong $pred" {
                withTest {
                    httpPut(orgPath) {
                        setTurtleBody("""
                            $validOrgBody
                            <> $pred $obj .
                        """.trimIndent())
                    }.apply {
                        response shouldHaveStatus HttpStatusCode.BadRequest
                    }
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

                        validateCreatedOrgTriples(response, orgId, orgName)
                    }
                }
            }
        }
    }
}
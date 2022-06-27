package org.openmbee.mms5

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
    }
}
package org.openmbee.mms5

import io.kotest.assertions.ktor.shouldHaveHeader
import io.kotest.assertions.ktor.shouldHaveStatus
import io.ktor.http.*
import org.openmbee.mms5.util.*
import java.util.*

class OrgRead : OrgAny() {
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

        "get all orgs" {
            val createBase = createOrg(orgId, orgName)
            val createFoo = createOrg(orgFooId, orgFooName)
            val createBar = createOrg(orgBarId, orgBarName)

            withTest {
                httpGet("/orgs") {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK

                    logger.info(response.content)

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
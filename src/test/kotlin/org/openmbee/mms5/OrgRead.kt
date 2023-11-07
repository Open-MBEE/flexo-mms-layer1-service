package org.openmbee.mms5

import io.kotest.assertions.ktor.client.shouldHaveETag
import io.kotest.assertions.ktor.client.shouldHaveStatus
import io.ktor.http.*
import io.ktor.server.testing.*
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
            testApplication {
                val created = createOrg(orgId, orgName)
                val response = get(orgPath){}
                response shouldHaveStatus HttpStatusCode.OK
                response shouldHaveETag created.headers[HttpHeaders.ETag]!!
                response includesTriples {
                    validateOrgTriples(orgId, orgName)
                }
            }
        }


        "get org if-match etag" {
            val etag = createOrg(orgId, orgName).response.headers[HttpHeaders.ETag]

            withTest {
                httpGet(orgPath) {
                    addHeader("If-Match", "\"${etag}\"")
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                }
            }
        }

        "get org if-match random" {
            createOrg(orgId, orgName).response.headers[HttpHeaders.ETag]

            withTest {
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
                    addHeader("If-None-Match", "\"${create.response.headers[HttpHeaders.ETag]!!}\"")
                }.apply {
                    logger.info(response.status().toString())
                    response shouldHaveStatus HttpStatusCode.NotModified
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

                    response.includesTriples {
                        modelName = it

                        validateOrgTriples(orgId, orgName)
                        validateOrgTriples(orgFooId, orgFooName)
                        validateOrgTriples(orgBarId, orgBarName)
                    }
                }
            }
        }
    }
}

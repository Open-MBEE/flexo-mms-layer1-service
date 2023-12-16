package org.openmbee.flexo.mms

import io.ktor.http.*
import org.openmbee.flexo.mms.util.*
import java.util.*

class OrgRead : OrgAny() {
    init {
        "head non-existent org" {
            withTest {
                httpHead(demoOrgPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }

        "get non-existent org" {
            withTest {
                httpGet(demoOrgPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }

        "head org" {
            val create = createOrg(demoOrgId, demoOrgName)

            withTest {
                httpHead(demoOrgPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response.shouldHaveHeader(HttpHeaders.ETag, create.response.headers[HttpHeaders.ETag]!!)
                }
            }
        }

        "get org" {
            val create = createOrg(demoOrgId, demoOrgName)

            withTest {
                httpGet(demoOrgPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response.shouldHaveHeader(HttpHeaders.ETag, create.response.headers[HttpHeaders.ETag]!!)

                    response includesTriples  {
                        validateOrgTriples(response, demoOrgId, demoOrgName)
                    }
                }
            }
        }


        "get org if-match etag" {
            val etag = createOrg(demoOrgId, demoOrgName).response.headers[HttpHeaders.ETag]

            withTest {
                httpGet(demoOrgPath) {
                    addHeader(HttpHeaders.IfMatch, "\"${etag}\"")
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                }
            }
        }

        "get org if-match random" {
            createOrg(demoOrgId, demoOrgName).response.headers[HttpHeaders.ETag]

            withTest {
                httpGet(demoOrgPath) {
                    addHeader(HttpHeaders.IfMatch, "\"${UUID.randomUUID()}\"")
                }.apply {
                    response shouldHaveStatus HttpStatusCode.PreconditionFailed
                }
            }
        }

        "get org if-none-match etag" {
            val create = createOrg(demoOrgId, demoOrgName)

            withTest {
                httpGet(demoOrgPath) {
                    addHeader(HttpHeaders.IfNoneMatch, "\"${create.response.headers[HttpHeaders.ETag]!!}\"")
                }.apply {
                    logger.info(response.status().toString())
                    response shouldHaveStatus HttpStatusCode.NotModified
                }
            }
        }

        "get org if-none-match star" {
            createOrg(demoOrgId, demoOrgName)

            withTest {
                httpGet(demoOrgPath) {
                    addHeader(HttpHeaders.IfNoneMatch, "*")
                }.apply {
                    response shouldHaveStatus HttpStatusCode.NotModified
                }
            }
        }

        "get all orgs" {
            val createBase = createOrg(demoOrgId, demoOrgName)
            val createFoo = createOrg(fooOrgId, fooOrgName)
            val createBar = createOrg(barOrgId, barOrgName)

            withTest {
                httpGet("/orgs") {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK

                    response.includesTriples {
                        modelName = it

                        validateOrgTriples(createBase.response, demoOrgId, demoOrgName)
                        validateOrgTriples(createFoo.response, fooOrgId, fooOrgName)
                        validateOrgTriples(createBar.response, barOrgId, barOrgName)
                    }
                }
            }
        }
    }
}
package org.openmbee.flexo.mms


import io.ktor.http.*
import org.openmbee.flexo.mms.util.*
import java.util.*


class OrgLdpDc : OrgAny() {
    init {
        LinkedDataPlatformDirectContainerTests(
            basePath = basePathOrgs,
            resourceId = demoOrgId,
            validBodyForCreate = validOrgBody
        ) {
            val orgCreator = { createOrg(demoOrgId, demoOrgName) }

            create {
                validateCreatedOrgTriples(it, demoOrgId, demoOrgName)
            }

            postWithPrecondition {
                response shouldHaveStatus HttpStatusCode.BadRequest
            }

            read(
                orgCreator,
                { createOrg(fooOrgId, fooOrgName) },
                { createOrg(barOrgId, barOrgName) },
            ) {
                it.response includesTriples {
                    validateOrgTriples(it.createdBase, demoOrgId, demoOrgName)
                }

                if(it.createdOthers.isNotEmpty()) {
                    it.response includesTriples {
                        validateOrgTriples(it.createdOthers[0], fooOrgId, fooOrgName)
                        validateOrgTriples(it.createdOthers[1], barOrgId, barOrgName)
                    }
                }
            }

            patch(orgCreator)
        }


        mapOf(
            "rdf:type" to "mms:NotOrg",
            "mms:id" to "\"not-$demoOrgId\"",
            "mms:etag" to "\"${UUID.randomUUID()}\"",
        ).forEach { (pred, obj) ->
            "reject wrong $pred".config(tags=setOf(NoAuth)) {
                withTest {
                    httpPut(demoOrgPath) {
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
    }
}
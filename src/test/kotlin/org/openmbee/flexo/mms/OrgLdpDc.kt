package org.openmbee.flexo.mms


import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.http.*
import org.openmbee.flexo.mms.util.*
import java.util.*


class OrgLdpDc : OrgAny() {
    init {
        LinkedDataPlatformDirectContainerTests(
            basePath = basePathOrgs,
            resourceId = demoOrgId,
            validBodyForCreate = validOrgBody,
            resourceCreator = { createOrg(demoOrgId, demoOrgName) }
        ) {
            create {response, slug ->
                validateCreatedOrgTriples(response, slug, demoOrgName)
            }

            postWithPrecondition {
                response shouldHaveStatus HttpStatusCode.BadRequest
            }

            read(
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

            patch()

//            delete()
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
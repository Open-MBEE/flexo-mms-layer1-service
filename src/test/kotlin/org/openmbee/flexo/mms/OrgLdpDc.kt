package org.openmbee.flexo.mms


import io.kotest.assertions.ktor.client.shouldHaveStatus
import io.ktor.http.*
import io.ktor.server.testing.*
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
                this shouldHaveStatus HttpStatusCode.BadRequest
            }

            replaceExisting {
                it includesTriples {
                    // will error if etag have multiple values or created/createdBy doesn't exist
                    validateOrgTriples(it, demoOrgId, demoOrgName)
                }
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
            "reject wrong $pred" {
                testApplication {
                    httpPut(demoOrgPath, true) {
                        setTurtleBody(withAllTestPrefixes("""
                            $validOrgBody
                            <> $pred $obj .
                        """.trimIndent()))
                    }.apply {
                        this shouldHaveStatus HttpStatusCode.BadRequest
                    }
                }
            }
        }
    }
}

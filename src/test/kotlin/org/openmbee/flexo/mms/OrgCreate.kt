package org.openmbee.flexo.mms


import io.ktor.http.*
import org.openmbee.flexo.mms.util.*
import java.util.*


class OrgCreate : OrgAny() {
    init {
        LinkedDataPlatformDirectContainerTests(
            basePath = basePathOrgs,
            resourceId = demoOrgId,
            validBodyForCreate = validOrgBody
        ) {
            create {
                validateCreatedOrgTriples(it, demoOrgId, demoOrgName)
            }

            postWithPrecondition {
                response shouldHaveStatus HttpStatusCode.BadRequest
            }
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
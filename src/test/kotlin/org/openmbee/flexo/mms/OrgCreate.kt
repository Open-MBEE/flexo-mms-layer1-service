package org.openmbee.flexo.mms


import io.ktor.http.*
import org.openmbee.flexo.mms.util.*
import java.util.*


class OrgCreate : OrgAny() {
    init {
        LinkedDataPlatformDirectContainerTests(
            basePath = "/orgs",
            resourceId = orgId,
            validBodyForCreate = validOrgBody
        ) {
            create {
                validateCreatedOrgTriples(it, orgId, orgName)
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
    }
}
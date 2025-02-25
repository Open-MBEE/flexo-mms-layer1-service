package org.openmbee.flexo.mms

import io.ktor.http.*
import org.openmbee.flexo.mms.util.*


class CommitLdpDc : CommitAny() {
    init {
        LinkedDataPlatformDirectContainerTests(
            basePath = basePathCommits,
            resourceId = "toBeReplaced",
            validBodyForCreate = "",
            resourceCreator = {
                commitModel("$demoRepoPath/branches/master", """
                    insert data { <urn:some> <urn:thing> <urn:here> . }
                """.trimIndent())
            },
            useCreatorLocationForResource = true,
        ) {
            read() {

            }

            patch()
        }

        "commit path only allow head get and patch" {
            val call = commitModel("$demoRepoPath/branches/master", """
                    insert data { <urn:some> <urn:thing> <urn:here> . }
                """.trimIndent())
            val path = call.response.headers[HttpHeaders.Location]!!.removePrefix(ROOT_CONTEXT)
            withTest {
                onlyAllowsMethods(path, setOf(
                    HttpMethod.Head,
                    HttpMethod.Get,
                    HttpMethod.Patch
                ))
            }
        }

        "base path only allow head get" {
            withTest {
                onlyAllowsMethods(basePathCommits, setOf(
                    HttpMethod.Head,
                    HttpMethod.Get,
                ))
            }
        }
    }
}
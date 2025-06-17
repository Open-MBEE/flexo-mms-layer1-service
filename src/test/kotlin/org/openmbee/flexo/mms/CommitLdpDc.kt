package org.openmbee.flexo.mms

import io.ktor.http.*
import io.ktor.server.testing.*
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
            read({ commitModel("$demoRepoPath/branches/master", """
                    insert data { <urn:some1> <urn:thing1> <urn:here1> . }
                """.trimIndent())}
            ) { bundle ->
                if (bundle.createdOthers.isEmpty()) {
                    bundle.response includesTriples {
                        validateCommitTriples(basePathCommits, bundle.createdBase.headers[HttpHeaders.Location]!!)
                    }
                }
                else {
                    bundle.response includesTriples {
                        bundle.createdOthers.forEach {
                            validateCommitTriples(basePathCommits, it.headers[HttpHeaders.Location]!!)
                        }
                    }
                }
            }

            patch()
        }

        "commit path only allow head get and patch" {
            testApplication {
                val call = commitModel("$demoRepoPath/branches/master", """
                    insert data { <urn:some> <urn:thing> <urn:here> . }
                """.trimIndent())
                val path = call.headers[HttpHeaders.Location]!!.removePrefix(ROOT_CONTEXT)
                onlyAllowsMethods(path, setOf(
                    HttpMethod.Head,
                    HttpMethod.Get,
                    HttpMethod.Patch
                ))
            }
        }

        "base path only allow head get" {
            testApplication {
                onlyAllowsMethods(basePathCommits, setOf(
                    HttpMethod.Head,
                    HttpMethod.Get,
                ))
            }
        }
    }
}
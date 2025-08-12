package org.openmbee.flexo.mms


import io.kotest.assertions.ktor.client.shouldHaveStatus
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.testing.*
import org.openmbee.flexo.mms.util.*

class LockRead : LockAny() {
    init {
        listOf(
            "head",
            "get",
            "patch",
//            "delete",
        ).forEach { method ->
            "$method non-existent lock" {
                testApplication {
                    httpRequest(HttpMethod(method.uppercase()), demoLockPath) {
                        // PATCH request
                        if (method == "patch") {
                            header("Content-Type", RdfContentTypes.Turtle.toString())
                        }
                    }.apply {
                        this shouldHaveStatus HttpStatusCode.NotFound
                    }
                }
            }
        }

        "head valid lock" {
            testApplication {
                createLock(demoRepoPath, masterBranchPath, demoLockId)
                httpHead(demoLockPath) {}.apply {
                    this shouldHaveStatus HttpStatusCode.NoContent
                    this.bodyAsText().shouldBe("")
                }
            }
        }

        "get valid lock" {
            testApplication {
                val etag = createLock(demoRepoPath, masterBranchPath, demoLockId).headers[HttpHeaders.ETag]
                httpGet(demoLockPath) {}.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this includesTriples {
                        validateLockTriples(demoLockId, etag!!)
                    }
                }
            }
        }

        "lock other methods not allowed" {
            testApplication {
                onlyAllowsMethods(demoLockPath, setOf(
                    HttpMethod.Head,
                    HttpMethod.Get,
                    HttpMethod.Put,
                    HttpMethod.Patch,
                    HttpMethod.Delete,
                ))
            }
        }

        "head all locks" {
            testApplication {
                createLock(demoRepoPath, masterBranchPath, demoLockId)
                httpHead("$demoRepoPath/locks") {}.apply {
                    this shouldHaveStatus HttpStatusCode.NoContent
                    this.bodyAsText().shouldBe("")
                }
            }
        }

        "get all locks" {
            testApplication {
                createLock(demoRepoPath, masterBranchPath, demoLockId)
                httpGet("$demoRepoPath/locks") {}.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this includesTriples {
                        validateLockTriples(demoLockId)
                    }
                }
            }
        }

        "all locks other methods not allowed" {
            testApplication {
                onlyAllowsMethods("$demoRepoPath/locks", setOf(
                    HttpMethod.Head,
                    HttpMethod.Get,
                    HttpMethod.Post,
                ))
            }
        }
    }
}

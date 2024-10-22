package org.openmbee.flexo.mms


import io.kotest.matchers.shouldBe
import io.ktor.http.*
import org.openmbee.flexo.mms.util.*

class LockRead : LockAny() {
    init {
        listOf(
            "head",
            "get",
            "patch",
            "delete",
        ).forEach { method ->
            "$method non-existent lock" {
                withTest {
                    httpRequest(HttpMethod(method.uppercase()), demoLockPath) {}.apply {
                        response shouldHaveStatus HttpStatusCode.NotFound
                    }
                }
            }
        }

        "head valid lock" {
            createLock(demoRepoPath, masterBranchPath, demoLockId)

            withTest {
                httpHead(demoLockPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.NoContent
                    response.content.shouldBe(null)
                }
            }
        }

        "get valid lock" {
            val etag = createLock(demoRepoPath, masterBranchPath, demoLockId).response.headers[HttpHeaders.ETag]

            withTest {
                httpGet(demoLockPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response includesTriples {
                        validateLockTriples(demoLockId, etag!!)
                    }
                }
            }
        }

        "lock other methods not allowed" {
            withTest {
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
            createLock(demoRepoPath, masterBranchPath, demoLockId)

            withTest {
                httpHead("$demoRepoPath/locks") {}.apply {
                    response shouldHaveStatus HttpStatusCode.NoContent
                    response.content.shouldBe(null)
                }
            }
        }

        "get all locks" {
            createLock(demoRepoPath, masterBranchPath, demoLockId)

            withTest {
                httpGet("$demoRepoPath/locks") {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response includesTriples {
                        validateLockTriples(demoLockId)
                    }
                }
            }
        }

        "all locks other methods not allowed" {
            withTest {
                onlyAllowsMethods("$demoRepoPath/locks", setOf(
                    HttpMethod.Head,
                    HttpMethod.Get,
                    HttpMethod.Post,
                ))
            }
        }
    }
}

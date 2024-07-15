package org.openmbee.flexo.mms


import io.ktor.http.*
import org.openmbee.flexo.mms.util.*

class LockRead : LockAny() {
    init {
        listOf(
            "head",
            "get",
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
                    response shouldHaveStatus HttpStatusCode.OK
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
    }
}

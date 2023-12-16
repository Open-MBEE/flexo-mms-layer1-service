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
                    httpRequest(HttpMethod(method.uppercase()), lockPath) {}.apply {
                        response shouldHaveStatus HttpStatusCode.NotFound
                    }
                }
            }
        }

        "head valid lock" {
            createLock(demoRepoPath, masterPath, lockId)

            withTest {
                httpHead(lockPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                }
            }
        }

        "get valid lock" {
            val etag = createLock(demoRepoPath, masterPath, lockId).response.headers[HttpHeaders.ETag]

            withTest {
                httpGet(lockPath) {}.apply {
                    response includesTriples {
                        thisLockTriples(lockId, etag!!)
                    }
                }
            }
        }
    }
}

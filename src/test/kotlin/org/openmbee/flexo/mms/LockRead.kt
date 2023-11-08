package org.openmbee.flexo.mms


import io.ktor.http.*
import org.apache.jena.vocabulary.RDF
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
            createLock(repoPath, masterPath, lockId)

            withTest {
                httpHead(lockPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                }
            }
        }

        "get valid lock" {
            val etag = createLock(repoPath, masterPath, lockId).response.headers[HttpHeaders.ETag]

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

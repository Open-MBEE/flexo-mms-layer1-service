package org.openmbee.flexo.mms


import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.http.*
import org.openmbee.flexo.mms.util.*

class LockCreate : LockAny() {
    fun createAndValidateLock(_lockId: String=demoLockId, lockBody: String=validLockBodyfromMaster) {
        withTest {
            httpPut("$demoRepoPath/locks/$_lockId") {
                setTurtleBody(withAllTestPrefixes(lockBody))
            }.apply {
                val etag = response.headers[HttpHeaders.ETag]
                etag.shouldNotBeBlank()

                response shouldHaveStatus HttpStatusCode.Created
                response.exclusivelyHasTriples {
                    modelName = "ValidateLock"
                    validateCreatedLockTriples(_lockId, etag!!, demoOrgPath)
                }
            }
        }
    }

    init {
        "reject invalid lock id".config(tags=setOf(NoAuth)) {
            withTest {
                httpPut("$demoLockPath with invalid id") {
                    setTurtleBody(withAllTestPrefixes(validLockBodyfromMaster))
                }.apply {
                    response shouldHaveStatus HttpStatusCode.BadRequest
                }
            }
        }

        "create lock on empty master" {
            createAndValidateLock()
        }

        "create lock on committed master" {
            commitModel(masterBranchPath, """
                insert data {
                    <urn:mms:s> <urn:mms:p> <urn:mms:o> .
                }
            """.trimIndent())

            createAndValidateLock()
        }

        "create lock on existing lock" {
            commitModel(masterBranchPath, """
                insert data {
                    <urn:mms:s> <urn:mms:p> <urn:mms:o> .
                }
            """.trimIndent())

            createAndValidateLock()

            createAndValidateLock("other-lock", "<> mms:ref <./$demoLockId> .")
        }
    }
}
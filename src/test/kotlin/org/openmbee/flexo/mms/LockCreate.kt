package org.openmbee.flexo.mms


import io.kotest.assertions.ktor.client.shouldHaveStatus
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.http.*
import io.ktor.server.testing.*
import org.openmbee.flexo.mms.util.*

class LockCreate : LockAny() {
    fun createAndValidateLock(_lockId: String=demoLockId, lockBody: String=validLockBodyfromMaster) {
        testApplication {
            httpPut("$demoRepoPath/locks/$_lockId") {
                setTurtleBody(withAllTestPrefixes(lockBody))
            }.apply {
                val etag = this.headers[HttpHeaders.ETag]
                etag.shouldNotBeBlank()

                this shouldHaveStatus HttpStatusCode.Created
                this.exclusivelyHasTriples {
                    modelName = "ValidateLock"
                    validateCreatedLockTriples(_lockId, etag!!, demoOrgPath)
                }
            }
        }
    }

    init {
        "reject invalid lock id" {
            testApplication {
                httpPut("$demoLockPath with invalid id", true) {
                    setTurtleBody(withAllTestPrefixes(validLockBodyfromMaster))
                }.apply {
                    this shouldHaveStatus HttpStatusCode.BadRequest
                }
            }
        }

        "create lock on empty master" {
            createAndValidateLock()
        }

        "create lock on committed master" {
            testApplication {
                commitModel(masterBranchPath, """
                    insert data {
                        <urn:mms:s> <urn:mms:p> <urn:mms:o> .
                    }
                """.trimIndent())
            }

            createAndValidateLock()
        }

        "create lock on existing lock" {
            testApplication {
                commitModel(masterBranchPath, """
                    insert data {
                        <urn:mms:s> <urn:mms:p> <urn:mms:o> .
                    }
                """.trimIndent())
            }

            createAndValidateLock()

            createAndValidateLock("other-lock", "<> mms:ref <./$demoLockId> .")
        }
    }
}
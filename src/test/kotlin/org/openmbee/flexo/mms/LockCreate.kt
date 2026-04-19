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

        "create lock from commit with existing model graph" {
            testApplication {
                val update = commitModel(masterBranchPath, """
                    insert data {
                        <urn:mms:s> <urn:mms:p> <urn:mms:o> .
                    }
                """.trimIndent())

                val commitId = update.headers[HttpHeaders.ETag]!!

                // create lock from commit (model graph exists via master's lock)
                httpPut("$demoRepoPath/locks/commit-lock") {
                    setTurtleBody(withAllTestPrefixes("""
                        <> mms:commit mor-commit:$commitId .
                    """.trimIndent()))
                }.apply {
                    val etag = this.headers[HttpHeaders.ETag]
                    etag.shouldNotBeBlank()

                    this shouldHaveStatus HttpStatusCode.Created
                    this.exclusivelyHasTriples {
                        modelName = "ValidateLockFromCommit"
                        validateCreatedLockTriples("commit-lock", etag!!, demoOrgPath)
                    }
                }
            }
        }
        "create lock from commit needing materialization" {
            testApplication {
                commitModel(masterBranchPath, """
                    insert data {
                        <urn:mms:s> <urn:mms:p> 1 .
                    }
                """.trimIndent())

                val update2 = commitModel(masterBranchPath, """
                    delete where {
                        <urn:mms:s> <urn:mms:p> ?previous .
                    } ;
                    insert data {
                        <urn:mms:s> <urn:mms:p> 2 .
                    }
                """.trimIndent())

                val commitId2 = update2.headers[HttpHeaders.ETag]!!

                kotlinx.coroutines.delay(2_000L)

                val update3 = commitModel(masterBranchPath, """
                    delete where {
                        <urn:mms:s> <urn:mms:p> ?previous .
                    } ;
                    insert data {
                        <urn:mms:s> <urn:mms:p> 3 .
                    }
                """.trimIndent())

                kotlinx.coroutines.delay(2_000L)

                // create lock from the 2nd commit (which may not have a model graph yet)
                httpPut("$demoRepoPath/locks/commit-lock") {
                    setTurtleBody(withAllTestPrefixes("""
                        <> mms:commit mor-commit:$commitId2 .
                    """.trimIndent()))
                }.apply {
                    val etag = this.headers[HttpHeaders.ETag]
                    etag.shouldNotBeBlank()

                    this shouldHaveStatus HttpStatusCode.Created
                    this.exclusivelyHasTriples {
                        modelName = "ValidateLockFromCommitMaterialized"
                        validateCreatedLockTriples("commit-lock", etag!!, demoOrgPath)
                    }
                }
            }
        }

        "create lock from commit with materialization after deleting auto locks" {
            testApplication {
                commitModel(masterBranchPath, """
                    insert data {
                        <urn:mms:s> <urn:mms:p> 1 .
                    }
                """.trimIndent())

                val update2 = commitModel(masterBranchPath, """
                    delete where {
                        <urn:mms:s> <urn:mms:p> ?previous .
                    } ;
                    insert data {
                        <urn:mms:s> <urn:mms:p> 2 .
                    }
                """.trimIndent())

                val commitId2 = update2.headers[HttpHeaders.ETag]!!

                kotlinx.coroutines.delay(2_000L)

                val update3 = commitModel(masterBranchPath, """
                    delete where {
                        <urn:mms:s> <urn:mms:p> ?previous .
                    } ;
                    insert data {
                        <urn:mms:s> <urn:mms:p> 3 .
                    }
                """.trimIndent())

                kotlinx.coroutines.delay(2_000L)

                // remove auto-created locks to force materialization codepath
                deleteAutoCreatedLocks(backend.getUpdateUrl(), demoRepoPath)

                // create lock from the 2nd commit (model graph no longer exists)
                httpPut("$demoRepoPath/locks/commit-lock") {
                    setTurtleBody(withAllTestPrefixes("""
                        <> mms:commit mor-commit:$commitId2 .
                    """.trimIndent()))
                }.apply {
                    val etag = this.headers[HttpHeaders.ETag]
                    etag.shouldNotBeBlank()

                    this shouldHaveStatus HttpStatusCode.Created
                    this.exclusivelyHasTriples {
                        modelName = "ValidateLockFromCommitMaterialized"
                        validateCreatedLockTriples("commit-lock", etag!!, demoOrgPath)
                    }
                }
            }
        }
    }
}

package org.openmbee.flexo.mms

import io.kotest.assertions.ktor.client.shouldHaveStatus
import io.ktor.http.*
import org.openmbee.flexo.mms.util.*

class LockDelete : LockAny() {
    private val lockCollectionId = "lock-ref-collection"
    private val lockCollectionPath = "$demoOrgPath/collections/$lockCollectionId"

    init {
        "delete valid lock returns 200" {
            testApplication {
                createLock(demoRepoPath, masterBranchPath, demoLockId)

                httpDelete(demoLockPath) {}.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                }
            }
        }

        "delete non-existent lock returns 404" {
            testApplication {
                httpDelete(demoLockPath) {}.apply {
                    this shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }

        "delete lock referenced by collection returns 409" {
            testApplication {
                createLock(demoRepoPath, masterBranchPath, demoLockId)

                createCollection(demoOrgPath, lockCollectionId, withAllTestPrefixes("""
                    <> dct:title "Lock Collection"@en .
                    <> mms:collects <$demoLockPath> .
                """.trimIndent()))

                httpDelete(demoLockPath) {}.apply {
                    this shouldHaveStatus HttpStatusCode.Conflict
                }
            }
        }

        "delete lock after removing collection reference succeeds" {
            testApplication {
                createLock(demoRepoPath, masterBranchPath, demoLockId)

                createCollection(demoOrgPath, lockCollectionId, withAllTestPrefixes("""
                    <> dct:title "Lock Collection"@en .
                    <> mms:collects <$demoLockPath> .
                """.trimIndent()))

                // replace collection to reference the branch instead of the lock
                httpPut(lockCollectionPath) {
                    setTurtleBody(withAllTestPrefixes("""
                        <> dct:title "Lock Collection"@en .
                        <> mms:collects <$masterBranchPath> .
                    """.trimIndent()))
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                }

                httpDelete(demoLockPath) {}.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                }
            }
        }

        "delete one of two locks on same commit leaves other intact" {
            val otherLockId = "other-lock"
            val otherLockPath = "$basePathLocks/$otherLockId"

            testApplication {
                createLock(demoRepoPath, masterBranchPath, demoLockId)
                val otherEtag = createLock(demoRepoPath, masterBranchPath, otherLockId).headers[HttpHeaders.ETag]

                httpDelete(demoLockPath) {}.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                }

                httpGet(otherLockPath) {}.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this includesTriples {
                        validateLockTriples(otherLockId, otherEtag!!)
                    }
                }
            }
        }

        "get deleted lock returns 404" {
            testApplication {
                createLock(demoRepoPath, masterBranchPath, demoLockId)

                httpDelete(demoLockPath) {}.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                }

                httpGet(demoLockPath) {}.apply {
                    this shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }
    }
}

package org.openmbee.flexo.mms

import io.kotest.assertions.ktor.client.shouldHaveStatus
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.ApplicationTestBuilder
import org.openmbee.flexo.mms.util.*


class SquashCommits : ModelAny() {
    init {
        "happy path: squash 3 commits into 2" {
            testApplication {
                // commit 1: insert Alice
                commitModel(masterBranchPath, insertAliceRex)

                // create lock at commit 1 (after first commit)
                createLock(demoRepoPath, "../branches/master", "lock-older")

                // commit 2: insert Bob
                commitModel(masterBranchPath, insertBobFluffy)

                // commit 3: insert more data
                commitModel(masterBranchPath, """
                    insert data {
                        <urn:mms:charlie> <urn:mms:name> "Charlie" .
                    }
                """.trimIndent())

                // create lock at commit 3 (after third commit)
                createLock(demoRepoPath, "../branches/master", "lock-newer")

                // verify the model graph before squash contains all data
                httpGet("$masterBranchPath/graph") {}.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this.includesTriples {
                        val people = demoPrefixes.get("")
                        subject("${people}Alice") {
                            ignoreAll()
                        }
                        subject("${people}Rex") {
                            ignoreAll()
                        }
                        subject("${people}Bob") {
                            ignoreAll()
                        }
                        subject("${people}Fluffy") {
                            ignoreAll()
                        }
                        subject("urn:mms:charlie") {
                            ignoreAll()
                        }
                    }
                }

                // delete auto-created locks on intermediate commits
                deleteAutoCreatedLocks(backend.getUpdateUrl(), demoRepoPath)

                // squash between the two locks
                httpPost("$demoRepoPath/squash", skipAnon = true) {
                    setTurtleBody("""
                        <> mms:srcRef mor-lock:lock-older .
                        <> mms:dstRef mor-lock:lock-newer .
                    """.trimIndent())
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                }

                // verify the model graph after squash is still intact
                httpGet("$masterBranchPath/graph") {}.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this.includesTriples {
                        val people = demoPrefixes.get("")
                        subject("${people}Alice") {
                            ignoreAll()
                        }
                        subject("${people}Rex") {
                            ignoreAll()
                        }
                        subject("${people}Bob") {
                            ignoreAll()
                        }
                        subject("${people}Fluffy") {
                            ignoreAll()
                        }
                        subject("urn:mms:charlie") {
                            ignoreAll()
                        }
                    }
                }
            }
        }

        "non-linear path returns 400" {
            testApplication {
                // commit on master
                commitModel(masterBranchPath, insertAliceRex)

                // create a branch from master
                createBranch(demoRepoPath, "master", demoBranchId, demoBranchName)

                // commit on the new branch
                commitModel(demoBranchPath, insertBobFluffy)

                // commit on master (diverging)
                commitModel(masterBranchPath, """
                    insert data {
                        <urn:mms:charlie> <urn:mms:name> "Charlie" .
                    }
                """.trimIndent())

                // create locks on different branches
                createLock(demoRepoPath, "../branches/master", "lock-master")
                createLock(demoRepoPath, "../branches/$demoBranchId", "lock-branch")

                // delete auto-created locks on intermediate commits
                deleteAutoCreatedLocks(backend.getUpdateUrl(), demoRepoPath)

                // attempt to squash across branches — should fail
                httpPost("$demoRepoPath/squash", skipAnon = true) {
                    setTurtleBody("""
                        <> mms:srcRef mor-lock:lock-master .
                        <> mms:dstRef mor-lock:lock-branch .
                    """.trimIndent())
                }.apply {
                    this shouldHaveStatus HttpStatusCode.BadRequest
                }
            }
        }

        "same commit returns 400" {
            testApplication {
                // commit on master
                commitModel(masterBranchPath, insertAliceRex)

                // create two locks pointing to the same commit
                createLock(demoRepoPath, "../branches/master", "lock-a")
                createLock(demoRepoPath, "../branches/master", "lock-b")

                // delete auto-created locks on intermediate commits
                deleteAutoCreatedLocks(backend.getUpdateUrl(), demoRepoPath)

                // attempt to squash — both locks point to the same commit
                httpPost("$demoRepoPath/squash", skipAnon = true) {
                    setTurtleBody("""
                        <> mms:srcRef mor-lock:lock-a .
                        <> mms:dstRef mor-lock:lock-b .
                    """.trimIndent())
                }.apply {
                    this shouldHaveStatus HttpStatusCode.BadRequest
                }
            }
        }

        "lock does not exist returns 404" {
            testApplication {
                // commit on master to have something
                commitModel(masterBranchPath, insertAliceRex)

                // create only one lock
                createLock(demoRepoPath, "../branches/master", "lock-exists")

                // delete auto-created locks on intermediate commits
                deleteAutoCreatedLocks(backend.getUpdateUrl(), demoRepoPath)

                // attempt to squash with non-existent lock
                httpPost("$demoRepoPath/squash", skipAnon = true) {
                    setTurtleBody("""
                        <> mms:srcRef mor-lock:lock-exists .
                        <> mms:dstRef mor-lock:lock-nonexistent .
                    """.trimIndent())
                }.apply {
                    this shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }

        "consecutive commits is a no-op" {
            testApplication {
                // commit 1: insert Alice
                commitModel(masterBranchPath, insertAliceRex)

                // create lock at commit 1
                createLock(demoRepoPath, "../branches/master", "lock-first")

                // commit 2: insert Bob (consecutive — no intermediate between lock-first and lock-second)
                commitModel(masterBranchPath, insertBobFluffy)

                // create lock at commit 2
                createLock(demoRepoPath, "../branches/master", "lock-second")

                // delete auto-created locks on intermediate commits
                deleteAutoCreatedLocks(backend.getUpdateUrl(), demoRepoPath)

                // squash between consecutive commits — should succeed as a no-op
                httpPost("$demoRepoPath/squash", skipAnon = true) {
                    setTurtleBody("""
                        <> mms:srcRef mor-lock:lock-first .
                        <> mms:dstRef mor-lock:lock-second .
                    """.trimIndent())
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                }

                // verify the model graph is still intact after no-op squash
                httpGet("$masterBranchPath/graph") {}.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this.includesTriples {
                        val people = demoPrefixes.get("")
                        subject("${people}Alice") {
                            ignoreAll()
                        }
                        subject("${people}Rex") {
                            ignoreAll()
                        }
                        subject("${people}Bob") {
                            ignoreAll()
                        }
                        subject("${people}Fluffy") {
                            ignoreAll()
                        }
                    }
                }
            }
        }
    }
}

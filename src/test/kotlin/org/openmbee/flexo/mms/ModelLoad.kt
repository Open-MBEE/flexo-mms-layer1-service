package org.openmbee.flexo.mms

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.ktor.http.*
import io.ktor.server.testing.*
import org.openmbee.flexo.mms.util.*

class ModelLoad : ModelAny() {
    init {
        "load all inserts on empty model" {
            withTest {
                httpPut("$masterBranchPath/graph") {
                    setTurtleBody(loadAliceRex)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                }
            }
        }

        "load no change on empty model" {
            withTest {
                httpPut("$masterBranchPath/graph") {
                    setTurtleBody("")
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK

                    // TODO - should still return diff
                }
            }
        }

        "load all inserts on non-empty model" {
            loadModel(masterBranchPath, loadAliceRex)

            withTest {
                httpPut("$masterBranchPath/graph") {
                    setTurtleBody("""
                        $loadAliceRex

                        :Xavier a :Person ;
                            foaf:name "Xavier" .
                    """.trimIndent())
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                }
            }
        }

        "load all deletes on non-empty model" {
            loadModel(masterBranchPath, loadAliceRex)

            withTest {
                httpPut("$masterBranchPath/graph") {
                    setTurtleBody("")
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                }
            }
        }

        "load no change on non-empty model" {
            loadModel(masterBranchPath, loadAliceRex)

            withTest {
                httpPut("$masterBranchPath/graph") {
                    setTurtleBody(loadAliceRex)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                }
            }
        }

        "load both inserts and deletes on non-empty model" {
            loadModel(masterBranchPath, loadAliceRex)

            withTest {
                httpPut("$masterBranchPath/graph") {
                    setTurtleBody("""
                        @prefix : <https://mms.openmbee.org/demos/people/>
                        @prefix foaf: <http://xmlns.com/foaf/0.1/>

                        :Xavier a :Person ;
                            foaf:name "Xavier" .
                    """.trimIndent())
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                }
            }
        }

        "head branch graph" {
            commitModel(masterBranchPath, insertAliceRex)

            withTest {
                httpHead("$masterBranchPath/graph") {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK
//                    response.content shouldBe null
                }
            }
        }

        "get branch graph" {
            commitModel(masterBranchPath, insertAliceRex)

            withTest {
                httpGet("$masterBranchPath/graph") {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK

                    response.exclusivelyHasTriples {
                        subjectTerse(":Alice") {
                            ignoreAll()
                        }

                        subjectTerse(":Rex") {
                            ignoreAll()
                        }
                    }
                }
            }
        }

        "head lock graph" {
            commitModel(masterBranchPath, insertAliceRex)
            createLock(demoRepoPath, masterBranchPath, demoLockId)

            withTest {
                httpHead("$demoLockPath/graph") {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response.content shouldBe null
                }
            }
        }

        "get lock graph" {
            commitModel(masterBranchPath, insertAliceRex)
            createLock(demoRepoPath, masterBranchPath, demoLockId)

            withTest {
                httpGet("$demoLockPath/graph") {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK

                    response.exclusivelyHasTriples {
                        subjectTerse(":Alice") {
                            ignoreAll()
                        }

                        subjectTerse(":Rex") {
                            ignoreAll()
                        }
                    }
                }
            }
        }

        "lock graph rejects other methods" {
            commitModel(masterBranchPath, insertAliceRex)
            createLock(demoRepoPath, masterBranchPath, demoLockId)

            withTest {
                onlyAllowsMethods("$demoLockPath/graph", setOf(
                    HttpMethod.Head,
                    HttpMethod.Get,
                ))
            }
        }
    }
}

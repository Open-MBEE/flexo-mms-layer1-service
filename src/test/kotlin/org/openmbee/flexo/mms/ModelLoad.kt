package org.openmbee.flexo.mms

import io.ktor.http.*
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
    }
}

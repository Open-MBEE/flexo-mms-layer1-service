package org.openmbee.mms5

import io.ktor.http.*
import org.openmbee.mms5.util.*

class ModelLoad : ModelAny() {
    init {
        "load all inserts on empty model" {
            withTest {
                httpPost("$masterPath/graph") {
                    setTurtleBody(loadTurtle)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                }
            }
        }

        "load no change on empty model" {
            withTest {
                httpPost("$masterPath/graph") {
                    setTurtleBody("")
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK

                    // TODO - should still return diff
                }
            }
        }

        "load all inserts on non-empty model" {
            loadModel(masterPath, loadTurtle)

            withTest {
                httpPost("$masterPath/graph") {
                    setTurtleBody("""
                        $loadTurtle
                        :Xavier a :Person ;
                            foaf:name "Xavier" .
                    """.trimIndent())
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                }
            }
        }

        "load all deletes on non-empty model" {
            loadModel(masterPath, loadTurtle)

            withTest {
                httpPost("$masterPath/graph") {
                    setTurtleBody("")
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                }
            }
        }

        "load no change on non-empty model" {
            loadModel(masterPath, loadTurtle)

            withTest {
                httpPost("$masterPath/graph") {
                    setTurtleBody(loadTurtle)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                }
            }
        }

        "load both inserts and deletes on non-empty model" {
            loadModel(masterPath, loadTurtle)

            withTest {
                httpPost("$masterPath/graph") {
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

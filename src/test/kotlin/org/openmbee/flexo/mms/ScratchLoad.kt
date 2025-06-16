package org.openmbee.flexo.mms

import io.kotest.core.test.TestCase
import io.kotest.matchers.shouldBe
import io.ktor.http.*

import org.openmbee.flexo.mms.util.*

//just tests, for the gsp endpoint in scratches.kt
class ScratchLoad: ScratchAny() {

    // create a scratch before each test
    override suspend fun beforeEach(testCase: TestCase) {
        super.beforeEach(testCase)
        createScratch(demoScratchPath, demoScratchName)
    }

    init{
        "load nothing on empty graph" {
            withTest {
                httpPut("$demoScratchPath/graph") {
                    setTurtleBody("")
                }.apply {
                    response shouldHaveStatus HttpStatusCode.NoContent
                }
            }
        }

        "load data on empty graph" {
            withTest {
                httpPut("$demoScratchPath/graph") {
                    setTurtleBody(loadAliceRex)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.NoContent
                }
            }
        }

        "load data on non-empty graph" {
            loadScratch(demoScratchPath, loadAliceRex)

            withTest {
                httpPut("$demoScratchPath/graph") {
                    setTurtleBody("""
                        $loadAliceRex

                        :Xavier a :Person ;
                            foaf:name "Xavier" .
                    """.trimIndent())
                }.apply {
                    response shouldHaveStatus HttpStatusCode.NoContent
                }
            }
        }

        "load clear on non-empty graph" {
            loadScratch(demoScratchPath, loadAliceRex)

            withTest {
                httpPut("$demoScratchPath/graph") {
                    setTurtleBody("")
                }.apply {
                    response shouldHaveStatus HttpStatusCode.NoContent
                }
            }
        }

//        "load no change on non-empty model" {
//            loadModel(demoScratchPath, loadAliceRex)
//
//            withTest {
//                httpPut("$demoScratchPath/graph") {
//                    setTurtleBody(loadAliceRex)
//                }.apply {
//                    response shouldHaveStatus HttpStatusCode.OK
//                }
//            }
//        }

//        "load both inserts and deletes on non-empty model" {
//            loadModel(demoScratchPath, loadAliceRex)
//
//            withTest {
//                httpPut("$demoScratchPath/graph") {
//                    setTurtleBody("""
//                        @prefix : <https://mms.openmbee.org/demos/people/>
//                        @prefix foaf: <http://xmlns.com/foaf/0.1/>
//
//                        :Xavier a :Person ;
//                            foaf:name "Xavier" .
//                    """.trimIndent())
//                }.apply {
//                    response shouldHaveStatus HttpStatusCode.OK
//                }
//            }
//        }

        "gsp endpoint for scratches rejects other methods" {
            withTest {
                onlyAllowsMethods(
                    "$demoScratchPath/graph", setOf(
                        HttpMethod.Head,
                        HttpMethod.Get,
                        HttpMethod.Put,
                    )
                )
            }
        }
    }
}

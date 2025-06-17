package org.openmbee.flexo.mms

import io.kotest.assertions.ktor.client.shouldHaveStatus
import io.kotest.core.test.TestCase
import io.kotest.matchers.shouldBe
import io.ktor.http.*
import io.ktor.server.testing.*

import org.openmbee.flexo.mms.util.*

//just tests, for the gsp endpoint in scratches.kt
class ScratchLoad: ScratchAny() {

    // create a scratch before each test
    override suspend fun beforeEach(testCase: TestCase) {
        super.beforeEach(testCase)
        testApplication {
            createScratch(demoScratchPath, demoScratchName)
        }
    }

    init{
        "load nothing on empty graph" {
            testApplication {
                httpPut("$demoScratchPath/graph") {
                    setTurtleBody("")
                }.apply {
                    this shouldHaveStatus HttpStatusCode.NoContent
                }
            }
        }

        "load data on empty graph" {
            testApplication {
                httpPut("$demoScratchPath/graph") {
                    setTurtleBody(loadAliceRex)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.NoContent
                }
            }
        }

        "load data on non-empty graph" {
            testApplication {
                loadScratch(demoScratchPath, loadAliceRex)
                httpPut("$demoScratchPath/graph") {
                    setTurtleBody("""
                        $loadAliceRex

                        :Xavier a :Person ;
                            foaf:name "Xavier" .
                    """.trimIndent())
                }.apply {
                    this shouldHaveStatus HttpStatusCode.NoContent
                }
            }
        }

        "load clear on non-empty graph" {
            testApplication {
                loadScratch(demoScratchPath, loadAliceRex)
                httpPut("$demoScratchPath/graph") {
                    setTurtleBody("")
                }.apply {
                    this shouldHaveStatus HttpStatusCode.NoContent
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
            testApplication {
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

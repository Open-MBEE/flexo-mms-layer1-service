package org.openmbee.flexo.mms

import io.kotest.assertions.ktor.client.shouldHaveStatus
import io.kotest.core.test.TestCase
import io.kotest.matchers.string.shouldContain
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.openmbee.flexo.mms.util.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ScratchQuery: ScratchAny() {

    // create an org before each repo test
    override suspend fun beforeEach(testCase: TestCase) {
        super.beforeEach(testCase)
        testApplication {
            createScratch(demoScratchPath, demoScratchName)
        }
    }

    init {
        "query data from scratch" {
            testApplication {
                updateScratch(demoScratchPath, insertAliceRex)
                httpPost("$demoScratchPath/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this shouldEqualSparqlResultsJson queryNamesAliceResult
                }
            }
        }

        "query scratch with graph var" {
            testApplication {
                updateScratch(demoScratchPath, insertAliceRex)
                // master model is updated
                httpPost("$demoScratchPath/query") {
                    setSparqlQueryBody("""
                        select distinct ?g {
                            graph ?g {
                                ?s ?p ?o
                            }
                        }
                    """.trimIndent())
                }.apply {
                    val graphVal = Json.parseToJsonElement(this.bodyAsText()).jsonObject["results"]!!
                        .jsonObject["bindings"]!!.jsonArray[0].jsonObject["g"]!!.jsonObject["value"]!!
                        .jsonPrimitive.content

                    graphVal shouldContain "/graphs/Scratch."

                    this shouldHaveStatus HttpStatusCode.OK
                    this equalsSparqlResults {
                        binding(
                            "g" to graphVal.bindingUri
                        )
                    }
                }
            }
        }

        "query scratch with from default not authorized" {
            testApplication {
                updateScratch(demoScratchPath, insertAliceRex)
                // scratch is updated??
                httpPost("$demoScratchPath/query") {
                    setSparqlQueryBody(withAllTestPrefixes("""
                        select *
                            from m-graph:AccessControl.Policies
                        {
                            ?s ?p ?o
                        }
                    """.trimIndent()))
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Forbidden
                }
            }
        }

        "query scratch with from named not authorized" {
            testApplication {
                updateScratch(demoScratchPath, insertAliceRex)
                // master model is updated
                httpPost("$demoScratchPath/query") {
                    setSparqlQueryBody(withAllTestPrefixes("""
                        select *
                            from named m-graph:AccessControl.Policies
                        {
                            graph ?g {
                                ?s ?p ?o
                            }
                        }
                    """.trimIndent()))
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Forbidden
                }
            }
        }

        "query scratch graph not there" {
            testApplication {
                updateScratch(demoScratchPath, insertAliceRex)
                // master model is updated
                httpPost("$demoScratchPath/query") {
                    setSparqlQueryBody(withAllTestPrefixes("""
                        select * {
                            graph m-graph:AccessControl.Policies {
                                ?s ?p ?o
                            }
                        }
                    """.trimIndent()))
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this equalsSparqlResults {
                        varsExpect.addAll(listOf(
                            "s", "p", "o"
                        ))
                    }
                }
            }
        }

        "ask scratch: true" {
            testApplication {
                updateScratch(demoScratchPath, insertAliceRex)
                httpPost("$demoScratchPath/query") {
                    setSparqlQueryBody("""
                        $demoPrefixesStr
                        
                        ask {
                            :Rex :owner ?who .
                        }
                    """.trimIndent())
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this shouldEqualSparqlResultsJson """
                        {
                            "head": {},
                            "boolean": true
                        }
                    """.trimIndent()
                }
            }
        }

        "ask scratch: false" {
            testApplication {
                updateScratch(demoScratchPath, insertAliceRex)
                httpPost("$demoScratchPath/query") {
                    setSparqlQueryBody("""
                        $demoPrefixesStr
                        
                        ask {
                            :Rex :owner :Bob .
                        }
                    """.trimIndent())
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this shouldEqualSparqlResultsJson """
                        {
                            "head": {},
                            "boolean": false
                        }
                    """.trimIndent()
                }
            }
        }

        "describe scratch explicit" {
            testApplication {
                updateScratch(demoScratchPath, insertAliceRex)
                httpPost("$demoScratchPath/query") {
                    setSparqlQueryBody("""
                        $demoPrefixesStr
                        
                        describe :Alice
                    """.trimIndent())
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this includesTriples  {
                        subjectTerse(":Alice") {
                            ignoreAll()
                        }
                    }
                }
            }
        }

        "describe scratch where" {
            testApplication {
                updateScratch(demoScratchPath, insertAliceRex)
                httpPost("$demoScratchPath/query") {
                    setSparqlQueryBody(
                        """
                        $demoPrefixesStr
                        
                        describe ?pet {
                            ?pet :owner :Alice .
                        }
                    """.trimIndent()
                    )
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this includesTriples {
                        subjectTerse(":Rex") {
                            ignoreAll()
                        }
                    }
                }
            }
        }

//        "subquery" {
//            commitScratch(demoScratchPath, insertAliceRex)
//            commitScratch(demoScratchPath, insertBobFluffy)
//
//            withTest {
//                httpPost("$demoScratchPath/query") {
//                    setSparqlQueryBody("""
//                        $demoPrefixesStr
//
//                        select ?personName {
//                            ?person foaf:name ?personName .
//
//                            {
//                                select * {
//                                    ?pet :owner ?person ;
//                                        :likes :Jelly .
//                                }
//                            }
//                        }
//                    """.trimIndent())
//                }.apply {
//                    response shouldHaveStatus HttpStatusCode.OK
//                    response equalsSparqlResults {
//                        binding(
//                            "personName" to "Bob".bindingLit
//                        )
//                    }
//                }
//            }
//        }

        "nothing exists" {
            testApplication {
                httpPost("/orgs/not-exists/repos/not-exists/scratches/not-exists/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }

        "concat" {
            testApplication {
                updateScratch(demoScratchPath, insertAliceRex)
                httpPost("$demoScratchPath/query") {
                    setSparqlQueryBody("""
                        $demoPrefixesStr

                        select ?concat {
                            :Alice foaf:name ?name .
                        
                            bind(concat(str("test:"), ?name) as ?concat)
                        }
                    """.trimIndent())
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this equalsSparqlResults {
                        binding(
                            "concat" to "test:Alice".bindingLit
                        )
                    }
                }
            }
        }
    }
}

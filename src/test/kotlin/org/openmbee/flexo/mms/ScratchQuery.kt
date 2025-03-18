package org.openmbee.flexo.mms

import io.kotest.matchers.string.shouldContain
import io.ktor.http.*
import org.openmbee.flexo.mms.util.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// just tests, but need more than modelQuery since there's an update route - lie - there's a separate ModelCommit file
class ScratchQuery: ScratchAny() {
    init {
        "query data from scratch" {
            commitScratch(demoScratchPath, insertAliceRex)
            withTest {
                httpPost("$demoScratchPath/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response shouldEqualSparqlResultsJson queryNamesAliceResult
                }
            }
        }

        "query scratch with graph var" {
            commitScratch(demoScratchPath, insertAliceRex)

            withTest {
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
                    val graphVal = Json.parseToJsonElement(response.content!!).jsonObject["results"]!!
                        .jsonObject["bindings"]!!.jsonArray[0].jsonObject["g"]!!.jsonObject["value"]!!
                        .jsonPrimitive.content

                    graphVal shouldContain "/graphs/Model."

                    response shouldHaveStatus HttpStatusCode.OK
                    response equalsSparqlResults {
                        binding(
                            "g" to graphVal.bindingUri
                        )
                    }
                }
            }
        }

        "query scratch with from default not authorized" {
            commitScratch(demoScratchPath, insertAliceRex)

            withTest {
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
                    response shouldHaveStatus HttpStatusCode.Forbidden
                }
            }
        }

        "query scratch with from named not authorized" {
            commitScratch(demoScratchPath, insertAliceRex)

            withTest {
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
                    response shouldHaveStatus HttpStatusCode.Forbidden
                }
            }
        }

        "query scratch graph not there" {
            commitScratch(demoScratchPath, insertAliceRex)

            withTest {
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
                    response shouldHaveStatus HttpStatusCode.OK
                    response equalsSparqlResults {
                        varsExpect.addAll(listOf(
                            "s", "p", "o"
                        ))
                    }
                }
            }
        }

        "ask scratch: true" {
            commitScratch(demoScratchPath, insertAliceRex)

            withTest {
                httpPost("$masterBranchPath/query") {
                    setSparqlQueryBody("""
                        $demoPrefixesStr
                        
                        ask {
                            :Rex :owner ?who .
                        }
                    """.trimIndent())
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response shouldEqualSparqlResultsJson """
                        {
                            "head": {},
                            "boolean": true
                        }
                    """.trimIndent()
                }
            }
        }

        "ask scratch: false" {
            commitScratch(demoScratchPath, insertAliceRex)

            withTest {
                httpPost("$demoScratchPath'/query") {
                    setSparqlQueryBody("""
                        $demoPrefixesStr
                        
                        ask {
                            :Rex :owner :Bob .
                        }
                    """.trimIndent())
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response shouldEqualSparqlResultsJson """
                        {
                            "head": {},
                            "boolean": false
                        }
                    """.trimIndent()
                }
            }
        }

        "describe scratch explicit" {
            commitScratch(demoScratchPath, insertAliceRex)

            withTest {
                httpPost("$demoScratchPath/query") {
                    setSparqlQueryBody("""
                        $demoPrefixesStr
                        
                        describe :Alice
                    """.trimIndent())
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response includesTriples  {
                        subjectTerse(":Alice") {
                            ignoreAll()
                        }
                    }
                }
            }
        }

        "describe scratch where" {
            commitScratch(demoScratchPath, insertAliceRex)

            withTest {
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
                    response shouldHaveStatus HttpStatusCode.OK
                    response includesTriples {
                        subjectTerse(":Rex") {
                            ignoreAll()
                        }
                    }
                }
            }
        }

        // Don't need branch tests, lock tests

        "subquery" {
            // FIXME these and some others were load instead - seems like those are triples if directly passing those in while these are the queries to insert
            commitScratch(demoScratchPath, insertAliceRex)
            commitScratch(demoScratchPath, insertBobFluffy)

            withTest {
                httpPost("$demoScratchPath/query") {
                    setSparqlQueryBody("""
                        $demoPrefixesStr
                        
                        select ?personName {
                            ?person foaf:name ?personName .
                        
                            {
                                select * {
                                    ?pet :owner ?person ;
                                        :likes :Jelly .
                                }
                            }
                        }
                    """.trimIndent())
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response equalsSparqlResults {
                        binding(
                            "personName" to "Bob".bindingLit
                        )
                    }
                }
            }
        }

        "nothing exists" {
            withTest {
                httpPost("/orgs/not-exists/repos/not-exists/scratches/not-exists/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }

        "concat" {
            commitScratch(demoScratchPath, insertAliceRex)

            withTest {
                httpPost("$demoScratchPath/query") {
                    setSparqlQueryBody("""
                        $demoPrefixesStr

                        select ?concat {
                            :Alice foaf:name ?name .
                        
                            bind(concat(str("test:"), ?name) as ?concat)
                        }
                    """.trimIndent())
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response equalsSparqlResults {
                        binding(
                            "concat" to "test:Alice".bindingLit
                        )
                    }
                }
            }
        }
    }
}

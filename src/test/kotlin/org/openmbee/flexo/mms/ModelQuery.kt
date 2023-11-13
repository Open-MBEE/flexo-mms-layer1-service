package org.openmbee.flexo.mms

import io.kotest.matchers.string.shouldContain
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openmbee.flexo.mms.util.*

class ModelQuery : ModelAny() {
    init {
        "query data from model" {
            val update = commitModel(masterPath, insertAliceRex)
            withTest {
                httpPost("$masterPath/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    response shouldEqualSparqlResultsJson queryNamesAliceResult
                }
            }
        }

        "query model with graph var" {
            commitModel(masterPath, insertAliceRex)

            withTest {
                // master model is updated
                httpPost("$masterPath/query") {
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

                    response equalsSparqlResults {
                        binding(
                            "g" to graphVal.bindingUri
                        )
                    }
                }
            }
        }

        "query model with from default not authorized" {
            commitModel(masterPath, insertAliceRex)

            withTest {
                // master model is updated
                httpPost("$masterPath/query") {
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

        "query model with from named not authorized" {
            commitModel(masterPath, insertAliceRex)

            withTest {
                // master model is updated
                httpPost("$masterPath/query") {
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

        "query model graph not there" {
            commitModel(masterPath, insertAliceRex)

            withTest {
                // master model is updated
                httpPost("$masterPath/query") {
                    setSparqlQueryBody(withAllTestPrefixes("""
                        select * {
                            graph m-graph:AccessControl.Policies {
                                ?s ?p ?o
                            }
                        }
                    """.trimIndent()))
                }.apply {
                    response equalsSparqlResults {
                        varsExpect.addAll(listOf(
                            "s", "p", "o"
                        ))
                    }
                }
            }
        }

        "ask model: true" {
            commitModel(masterPath, insertAliceRex)

            withTest {
                httpPost("$masterPath/query") {
                    setSparqlQueryBody("""
                        $demoPrefixesStr
                        
                        ask {
                            :Rex :owner ?who .
                        }
                    """.trimIndent())
                }.apply {
                    response shouldEqualSparqlResultsJson """
                        {
                            "head": {},
                            "boolean": true
                        }
                    """.trimIndent()
                }
            }
        }

        "ask model: false" {
            commitModel(masterPath, insertAliceRex)

            withTest {
                httpPost("$masterPath/query") {
                    setSparqlQueryBody("""
                        $demoPrefixesStr
                        
                        ask {
                            :Rex :owner :Bob .
                        }
                    """.trimIndent())
                }.apply {
                    response shouldEqualSparqlResultsJson """
                        {
                            "head": {},
                            "boolean": false
                        }
                    """.trimIndent()
                }
            }
        }

        "describe model explicit" {
            commitModel(masterPath, insertAliceRex)

            withTest {
                httpPost("$masterPath/query") {
                    setSparqlQueryBody("""
                        $demoPrefixesStr
                        
                        describe :Alice
                    """.trimIndent())
                }.apply {
                    response includesTriples  {
                        subjectTerse(":Alice") {
                            ignoreAll()
                        }
                    }
                }
            }
        }

        "describe model where" {
            commitModel(masterPath, insertAliceRex)

            withTest {
                httpPost("$masterPath/query") {
                    setSparqlQueryBody("""
                        $demoPrefixesStr
                        
                        describe ?pet {
                            ?pet :owner :Alice .
                        }
                    """.trimIndent())
                }.apply {
                    response includesTriples {
                        subjectTerse(":Rex") {
                            ignoreAll()
                        }
                    }
                }
            }
        }

        "query result is different between master and branch" {
            commitModel(masterPath, insertAliceRex)
            createBranch(repoPath, "master", branchId, branchName)
            commitModel(masterPath, insertBobFluffy)

            withTest {
                // branch model does not have second updates
                httpPost("$branchPath/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    response shouldEqualSparqlResultsJson queryNamesAliceResult
                }
                // master model is updated
                httpPost("$masterPath/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    response shouldEqualSparqlResultsJson queryNamesAliceBobResult
                }
            }
        }

        "query result is different between master and lock" {
            commitModel(masterPath, insertAliceRex)
            createLock(repoPath, masterPath, lockId)
            commitModel(masterPath, insertBobFluffy)

            withTest {
                // branch model does not have second updates
                httpPost("$lockPath/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    response shouldEqualSparqlResultsJson queryNamesAliceResult
                }

                // master model is updated
                httpPost("$masterPath/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    response shouldEqualSparqlResultsJson queryNamesAliceBobResult
                }
            }
        }

        "query result is different between master and lock from model loads" {
            loadModel(masterPath, loadAliceRex)
            createLock(repoPath, masterPath, lockId)
            loadModel(masterPath, loadBobFluffy)

            withTest {
                httpPost("$lockPath/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    response shouldEqualSparqlResultsJson queryNamesAliceResult
                }

                httpPost("$masterPath/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    // the load overwrites, so only bob exists
                    response equalsSparqlResults {
                        binding(
                            "name" to "Bob".bindingLit
                        )
                    }
                }
            }
        }

        "subquery" {
            loadModel(masterPath, loadAliceRex)
            loadModel(masterPath, loadBobFluffy)

            withTest {
                httpPost("$masterPath/query") {
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
                httpPost("/orgs/not-exists/repos/not-exists/branches/not-exists/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }

        "concat" {
            loadModel(masterPath, loadAliceRex)

            withTest {
                httpPost("$masterPath/query") {
                    setSparqlQueryBody("""
                        $demoPrefixesStr

                        select ?concat {
                            :Alice foaf:name ?name .
                        
                            bind(concat(str("test:"), ?name) as ?concat)
                        }
                    """.trimIndent())
                }.apply {
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

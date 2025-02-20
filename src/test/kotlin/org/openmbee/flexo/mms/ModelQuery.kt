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
            val update = commitModel(masterBranchPath, insertAliceRex)
            withTest {
                httpPost("$masterBranchPath/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response shouldEqualSparqlResultsJson queryNamesAliceResult
                }
            }
        }

        "query model with graph var" {
            commitModel(masterBranchPath, insertAliceRex)

            withTest {
                // master model is updated
                httpPost("$masterBranchPath/query") {
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

        "query model with from default not authorized" {
            commitModel(masterBranchPath, insertAliceRex)

            withTest {
                // master model is updated
                httpPost("$masterBranchPath/query") {
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
            commitModel(masterBranchPath, insertAliceRex)

            withTest {
                // master model is updated
                httpPost("$masterBranchPath/query") {
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
            commitModel(masterBranchPath, insertAliceRex)

            withTest {
                // master model is updated
                httpPost("$masterBranchPath/query") {
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

        "ask model: true" {
            commitModel(masterBranchPath, insertAliceRex)

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

        "ask model: false" {
            commitModel(masterBranchPath, insertAliceRex)

            withTest {
                httpPost("$masterBranchPath/query") {
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

        "describe model explicit" {
            commitModel(masterBranchPath, insertAliceRex)

            withTest {
                httpPost("$masterBranchPath/query") {
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

        "describe model where" {
            commitModel(masterBranchPath, insertAliceRex)

            withTest {
                httpPost("$masterBranchPath/query") {
                    setSparqlQueryBody("""
                        $demoPrefixesStr
                        
                        describe ?pet {
                            ?pet :owner :Alice .
                        }
                    """.trimIndent())
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

        "query result is different between master and branch" {
            commitModel(masterBranchPath, insertAliceRex)
            createBranch(demoRepoPath, "master", demoBranchId, demoBranchName)
            commitModel(masterBranchPath, insertBobFluffy)

            withTest {
                // branch model does not have second updates
                httpPost("$demoBranchPath/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response shouldEqualSparqlResultsJson queryNamesAliceResult
                }
                // master model is updated
                httpPost("$masterBranchPath/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response shouldEqualSparqlResultsJson queryNamesAliceBobResult
                }
            }
        }

        "query result is different between master and lock" {
            commitModel(masterBranchPath, insertAliceRex)
            createLock(demoRepoPath, masterBranchPath, demoLockId)
            commitModel(masterBranchPath, insertBobFluffy)

            withTest {
                // branch model does not have second updates
                httpPost("$demoLockPath/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response shouldEqualSparqlResultsJson queryNamesAliceResult
                }

                // master model is updated
                httpPost("$masterBranchPath/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response shouldEqualSparqlResultsJson queryNamesAliceBobResult
                }
            }
        }

        "query result is different between master and lock from model loads" {
            loadModel(masterBranchPath, loadAliceRex)
            createLock(demoRepoPath, masterBranchPath, demoLockId)
            loadModel(masterBranchPath, loadBobFluffy)

            withTest {
                httpPost("$demoLockPath/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response shouldEqualSparqlResultsJson queryNamesAliceResult
                }

                httpPost("$masterBranchPath/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK

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
            loadModel(masterBranchPath, loadAliceRex)
            loadModel(masterBranchPath, loadBobFluffy)

            withTest {
                httpPost("$masterBranchPath/query") {
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
                httpPost("/orgs/not-exists/repos/not-exists/branches/not-exists/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }

        "concat" {
            loadModel(masterBranchPath, loadAliceRex)

            withTest {
                httpPost("$masterBranchPath/query") {
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

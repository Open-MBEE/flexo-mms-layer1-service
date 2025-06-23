package org.openmbee.flexo.mms

import io.kotest.assertions.ktor.client.shouldHaveStatus
import io.kotest.matchers.string.shouldContain
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openmbee.flexo.mms.util.*

class ModelQuery : ModelAny() {
    init {
        "query data from model" {
            testApplication {
                val update = commitModel(masterBranchPath, insertAliceRex)
                httpPost("$masterBranchPath/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this shouldEqualSparqlResultsJson queryNamesAliceResult
                }
            }
        }

        "query model with graph var" {
            testApplication {
                commitModel(masterBranchPath, insertAliceRex)
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
                    val graphVal = Json.parseToJsonElement(this.bodyAsText()).jsonObject["results"]!!
                        .jsonObject["bindings"]!!.jsonArray[0].jsonObject["g"]!!.jsonObject["value"]!!
                        .jsonPrimitive.content
                    graphVal shouldContain "/graphs/Model."
                    this shouldHaveStatus HttpStatusCode.OK
                    this equalsSparqlResults {
                        binding(
                            "g" to graphVal.bindingUri
                        )
                    }
                }
            }
        }

        "query model with from default not authorized" {
            testApplication {
                commitModel(masterBranchPath, insertAliceRex)
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
                    this shouldHaveStatus HttpStatusCode.Forbidden
                }
            }
        }

        "query model with from named not authorized" {
            testApplication {
                commitModel(masterBranchPath, insertAliceRex)
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
                    this shouldHaveStatus HttpStatusCode.Forbidden
                }
            }
        }

        "query model graph not there" {
            testApplication {
                commitModel(masterBranchPath, insertAliceRex)
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
                    this shouldHaveStatus HttpStatusCode.OK
                    this equalsSparqlResults {
                        varsExpect.addAll(listOf(
                            "s", "p", "o"
                        ))
                    }
                }
            }
        }

        "ask model: true" {
            testApplication {
                commitModel(masterBranchPath, insertAliceRex)
                httpPost("$masterBranchPath/query") {
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

        "ask model: false" {
            testApplication {
                commitModel(masterBranchPath, insertAliceRex)
                httpPost("$masterBranchPath/query") {
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

        "describe model explicit" {
            testApplication {
                commitModel(masterBranchPath, insertAliceRex)
                httpPost("$masterBranchPath/query") {
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

        "describe model where" {
            testApplication {
                commitModel(masterBranchPath, insertAliceRex)
                httpPost("$masterBranchPath/query") {
                    setSparqlQueryBody("""
                        $demoPrefixesStr
                        
                        describe ?pet {
                            ?pet :owner :Alice .
                        }
                    """.trimIndent())
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

        "query result is different between master and branch" {
            testApplication {
                commitModel(masterBranchPath, insertAliceRex)
                createBranch(demoRepoPath, "master", demoBranchId, demoBranchName)
                commitModel(masterBranchPath, insertBobFluffy)
                // branch model does not have second updates
                httpPost("$demoBranchPath/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this shouldEqualSparqlResultsJson queryNamesAliceResult
                }
                // master model is updated
                httpPost("$masterBranchPath/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this shouldEqualSparqlResultsJson queryNamesAliceBobResult
                }
            }
        }

        "query result is different between master and lock" {
            testApplication {
                commitModel(masterBranchPath, insertAliceRex)
                createLock(demoRepoPath, masterBranchPath, demoLockId)
                commitModel(masterBranchPath, insertBobFluffy)
                // branch model does not have second updates
                httpPost("$demoLockPath/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this shouldEqualSparqlResultsJson queryNamesAliceResult
                }

                // master model is updated
                httpPost("$masterBranchPath/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this shouldEqualSparqlResultsJson queryNamesAliceBobResult
                }
            }
        }

        "query result is different between master and lock from model loads" {
            testApplication {
                loadModel(masterBranchPath, loadAliceRex)
                createLock(demoRepoPath, masterBranchPath, demoLockId)
                loadModel(masterBranchPath, loadBobFluffy)
                httpPost("$demoLockPath/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this shouldEqualSparqlResultsJson queryNamesAliceResult
                }

                httpPost("$masterBranchPath/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK

                    // the load overwrites, so only bob exists
                    this equalsSparqlResults {
                        binding(
                            "name" to "Bob".bindingLit
                        )
                    }
                }
            }
        }

        "subquery" {
            testApplication {
                loadModel(masterBranchPath, loadAliceRex)
                loadModel(masterBranchPath, loadBobFluffy)
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
                    this shouldHaveStatus HttpStatusCode.OK
                    this equalsSparqlResults {
                        binding(
                            "personName" to "Bob".bindingLit
                        )
                    }
                }
            }
        }

        "nothing exists" {
            testApplication {
                httpPost("/orgs/not-exists/repos/not-exists/branches/not-exists/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }

        "concat" {
            testApplication {
                loadModel(masterBranchPath, loadAliceRex)
                httpPost("$masterBranchPath/query") {
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

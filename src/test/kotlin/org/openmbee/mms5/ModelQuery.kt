package org.openmbee.mms5

import io.kotest.matchers.string.shouldContain
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openmbee.mms5.util.*

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

        "query selects model graph" {
            commitModel(masterPath, insertAliceRex)

            withTest {
                // master model is updated
                httpPost("$masterPath/query") {
                    setSparqlQueryBody("""
                        select ?g {
                            graph ?g {
                                ?s ?p ?o
                            }
                        }
                    """.trimIndent())
                }.apply {
//                    val modelGraphIri = Json.parseToJsonElement(response.content!!).jsonObject["results"]!!
//                        .jsonObject["bindings"]!!.jsonArray[0].jsonObject["g"]!!.jsonObject["value"]!!
//                        .jsonPrimitive.content
//
//                    modelGraphIri shouldContain "/Model."
//
//                    response shouldEqualSparqlResultsJson """
//                        {
//                            "head": {
//                                "vars": [
//                                    "g"
//                                ]
//                            },
//                            "results": {
//                                "bindings": [
//                                    {
//                                        "g": {
//                                            "type": "uri",
//                                            "value": "$modelGraphIri"
//                                        }
//                                    }
//                                ]
//                            }
//                        }
//                    """.trimIndent()
                }
            }
        }

        "query result is different between master and branch" {
            commitModel(masterPath, insertAliceRex)
            createBranch(repoPath, "master", branchId, branchName)
            commitModel(masterPath, insertBobFluffy)
            withTest {
                //branch model does not have second updates
                httpPost("$branchPath/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    response shouldEqualSparqlResultsJson queryNamesAliceResult
                }
                //master model is updated
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
                //branch model does not have second updates
                httpPost("$lockPath/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    response shouldEqualSparqlResultsJson queryNamesAliceResult
                }
                //master model is updated
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
                    response shouldEqualSparqlResultsJson queryNamesBobResult
                }
            }
        }

        "subquery" {
            loadModel(masterPath, loadAliceRex)
            withTest {
                httpPost("$masterPath/query") {
                    setSparqlQueryBody("""
                        SELECT * {
                            {
                                SELECT * WHERE {
                                    ?s ?p ?o
                                }
                            }
                        }
                    """.trimIndent())
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
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
                        prefix : <https://mms.openmbee.org/demos/people/>
                        prefix foaf: <http://xmlns.com/foaf/0.1/>
                        select ?concat {
                            :Alice foaf:name ?name .
                        
                            bind(concat(str("test:"), ?name) as ?concat)
                        }
                    """.trimIndent())
                }.apply {
                    response shouldEqualSparqlResultsJson """
                        {
                            "head": {
                                "vars": ["concat"]
                            },
                            "results": {
                                "bindings": [
                                    {
                                        "concat": {
                                            "type": "literal",
                                            "value": "test:Alice"
                                        }
                                    }
                                ]
                            }
                        }
                    """.trimIndent()
                }
            }
        }
    }
}

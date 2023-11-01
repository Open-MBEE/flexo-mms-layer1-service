package org.openmbee.mms5

import io.kotest.assertions.json.shouldMatchJson
import io.ktor.http.*
import org.openmbee.mms5.util.*

class ModelQuery : ModelAny() {
    init {
        "query data from model" {
            val update = commitModel(masterPath, insertAliceRex)
            withTest {
                httpPost("$masterPath/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    validateModelQueryResponse(queryNamesAliceResult)
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
                    validateModelQueryResponse(queryNamesAliceResult)
                }
                //master model is updated
                httpPost("$masterPath/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    validateModelQueryResponse(queryNamesAliceBobResult)
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
                    validateModelQueryResponse(queryNamesAliceResult)
                }
                //master model is updated
                httpPost("$masterPath/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    validateModelQueryResponse(queryNamesAliceBobResult)
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
                    validateModelQueryResponse(queryNamesAliceResult)
                }
                httpPost("$masterPath/query") {
                    setSparqlQueryBody(queryNames)
                }.apply {
                    // the load overwrites, so only bob exists
                    validateModelQueryResponse(queryNamesBobResult)
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
                    response.content shouldMatchJson """
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

package org.openmbee.mms5

import io.kotest.assertions.json.shouldMatchJson
import io.ktor.http.*
import org.openmbee.mms5.util.*

class ModelQuery : ModelAny() {
    init {
        "query data from model" {
            val update = commitModel(masterPath, sparqlUpdate)
            withTest {
                httpPost("$masterPath/query") {
                    setSparqlQueryBody(sparqlQueryNames)
                }.apply {
                    validateModelQueryResponse(sparqlQueryNamesResult)
                }
            }
        }

        "query result is different between master and branch" {
            commitModel(masterPath, sparqlUpdate)
            createBranch(repoPath, "master", branchId, branchName)
            commitModel(masterPath, sparqlUpdate2)
            withTest {
                //branch model does not have second updates
                httpPost("$branchPath/query") {
                    setSparqlQueryBody(sparqlQueryNames)
                }.apply {
                    validateModelQueryResponse(sparqlQueryNamesResult)
                }
                //master model is updated
                httpPost("$masterPath/query") {
                    setSparqlQueryBody(sparqlQueryNames)
                }.apply {
                    validateModelQueryResponse(sparqlQueryNamesResult2)
                }
            }
        }

        "query result is different between master and lock" {
            commitModel(masterPath, sparqlUpdate)
            createLock(repoPath, masterPath, lockId)
            commitModel(masterPath, sparqlUpdate2)
            withTest {
                //branch model does not have second updates
                httpPost("$lockPath/query") {
                    setSparqlQueryBody(sparqlQueryNames)
                }.apply {
                    validateModelQueryResponse(sparqlQueryNamesResult)
                }
                //master model is updated
                httpPost("$masterPath/query") {
                    setSparqlQueryBody(sparqlQueryNames)
                }.apply {
                    validateModelQueryResponse(sparqlQueryNamesResult2)
                }
            }
        }

        "query result is different between master and lock from model loads" {
            loadModel(masterPath, loadTurtle)
            createLock(repoPath, masterPath, lockId)
            loadModel(masterPath, loadTurtle2)
            withTest {
                httpPost("$lockPath/query") {
                    setSparqlQueryBody(sparqlQueryNames)
                }.apply {
                    validateModelQueryResponse(sparqlQueryNamesResult)
                }
                httpPost("$masterPath/query") {
                    setSparqlQueryBody(sparqlQueryNames)
                }.apply {
                    // the load overwrites, so only bob exists
                    validateModelQueryResponse(sparqlQueryNamesResultBob)
                }
            }
        }

        "subquery" {
            loadModel(masterPath, loadTurtle)
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

        "concat" {
            loadModel(masterPath, loadTurtle)
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

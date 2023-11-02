package org.openmbee.mms5

import io.kotest.assertions.json.shouldEqualJson
import io.kotest.assertions.json.shouldMatchJson

import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openmbee.mms5.util.*

class LockQuery : LockAny() {
    init {
        "query lock" {
            commitModel(masterPath, insertLock)
            createLock(repoPath, masterPath, lockId)

            withTest {
                httpPost("$lockPath/query") {
                    setSparqlQueryBody("""
                        select ?o {
                            <urn:mms:s> <urn:mms:p> ?o .
                        }
                    """.trimIndent())
                }.apply {
                    response shouldEqualSparqlResultsJson """
                        {
                            "head": {
                                "vars": ["o"]
                            },
                            "results": {
                                "bindings": [
                                    {
                                        "o": {
                                            "type": "uri",
                                            "value": "urn:mms:o"
                                        }
                                    }
                                ]
                            }
                        }
                    """.trimIndent()
                }
            }
        }

        "query lock with graph var" {
            commitModel(masterPath, insertLock)
            createLock(repoPath, masterPath, lockId)

            withTest {
                httpPost("$lockPath/query") {
                    setSparqlQueryBody("""
                        select ?g ?o {
                            graph ?g {
                                <urn:mms:s> <urn:mms:p> ?o .
                            }
                        }
                    """.trimIndent())
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK

                    val graphVal = Json.parseToJsonElement(response.content!!).jsonObject["results"]!!
                        .jsonObject["bindings"]!!.jsonArray[0].jsonObject["g"]!!.jsonObject["value"]!!
                        .jsonPrimitive.content;

                    response.content!!.shouldEqualJson("""
                        {
                            "head": {
                                "vars": ["g", "o"]
                            },
                            "results": {
                                "bindings": [
                                    {
                                        "g": {
                                            "type": "uri",
                                            "value": "$graphVal"
                                        },
                                        "o": {
                                            "type": "uri",
                                            "value": "urn:mms:o"
                                        }
                                    }
                                ]
                            }
                        }
                    """.trimIndent())
                }
            }
        }

        "ask lock" {
            commitModel(masterPath, insertLock)
            createLock(repoPath, masterPath, lockId)

            withTest {
                httpPost("$lockPath/query") {
                    setSparqlQueryBody("""
                        ask {
                            <urn:mms:s> <urn:mms:p> ?o .
                        }
                    """.trimIndent())
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK

                    response.content!!.shouldEqualJson("""
                        {
                            "head": {},
                            "boolean": true
                        }
                    """.trimIndent())
                }
            }
        }

        "describe lock explicit" {
            commitModel(masterPath, insertLock)
            createLock(repoPath, masterPath, lockId)

            withTest {
                httpPost("$lockPath/query") {
                    setSparqlQueryBody("""
                        describe <urn:mms:s>
                    """.trimIndent())
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK

                    response includesTriples {
                        subject("urn:mms:s") {
                            ignoreAll()
                        }
                    }
                }
            }
        }

        "describe lock where" {
            commitModel(masterPath, insertLock)
            createLock(repoPath, masterPath, lockId)

            withTest {
                httpPost("$lockPath/query") {
                    setSparqlQueryBody("""
                        describe ?s {
                            ?s <urn:mms:p> <urn:mms:o> .
                        }
                    """.trimIndent())
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK

                    response includesTriples {
                        subject("urn:mms:s") {
                            ignoreAll()
                        }
                    }
                }
            }
        }

        "construct lock" {
            commitModel(masterPath, insertLock)
            createLock(repoPath, masterPath, lockId)

            withTest {
                httpPost("$lockPath/query") {
                    setSparqlQueryBody("""
                        construct {
                            ?o ?p ?s .
                        } where {
                            ?s ?p ?o .
                        }
                    """.trimIndent())
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK

                    response exclusivelyHasTriples  {
                        subject("urn:mms:o") {
                            exclusivelyHas(
                                "urn:mms:p".toPredicate exactly "urn:mms:s".iri
                            )
                        }
                    }
                }
            }
        }

        "nothing exists" {
            withTest {
                httpPost("/orgs/not-exists/repos/not-exists/locks/not-exists/query") {
                    setSparqlQueryBody("""
                        select ?o {
                            <urn:mms:s> <urn:mms:p> ?o .
                        }
                    """)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }
    }
}

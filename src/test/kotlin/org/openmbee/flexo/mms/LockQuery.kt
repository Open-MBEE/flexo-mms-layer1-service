package org.openmbee.flexo.mms

import io.kotest.matchers.string.shouldContain
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openmbee.flexo.mms.util.*

class LockQuery : LockAny() {
    init {
        "query lock" {
            commitModel(masterBranchPath, insertLock)
            createLock(demoRepoPath, masterBranchPath, demoLockId)

            withTest {
                httpPost("$demoLockPath/query") {
                    setSparqlQueryBody("""
                        select ?o {
                            <urn:mms:s> <urn:mms:p> ?o .
                        }
                    """.trimIndent())
                }.apply {
                    response equalsSparqlResults {
                        binding(
                            "o" to "urn:mms:o".bindingUri
                        )
                    }
                }
            }
        }

        "query lock with graph var" {
            commitModel(masterBranchPath, insertLock)
            createLock(demoRepoPath, masterBranchPath, demoLockId)

            withTest {
                httpPost("$demoLockPath/query") {
                    setSparqlQueryBody("""
                        select ?g ?o {
                            graph ?g {
                                <urn:mms:s> <urn:mms:p> ?o .
                            }
                        }
                    """.trimIndent())
                }.apply {
                    val graphVal = Json.parseToJsonElement(response.content!!).jsonObject["results"]!!
                        .jsonObject["bindings"]!!.jsonArray[0].jsonObject["g"]!!.jsonObject["value"]!!
                        .jsonPrimitive.content;

                    graphVal shouldContain "/graphs/Model."

                    response equalsSparqlResults {
                        binding(
                            "g" to graphVal.bindingUri,
                            "o" to "urn:mms:o".bindingUri
                        )
                    }
                }
            }
        }

        "ask lock: true" {
            commitModel(masterBranchPath, insertLock)
            createLock(demoRepoPath, masterBranchPath, demoLockId)

            withTest {
                httpPost("$demoLockPath/query") {
                    setSparqlQueryBody("""
                        ask {
                            <urn:mms:s> <urn:mms:p> ?o .
                        }
                    """.trimIndent())
                }.apply {
                    response shouldEqualSparqlResultsJson  """
                        {
                            "head": {},
                            "boolean": true
                        }
                    """.trimIndent()
                }
            }
        }

        "ask lock: false" {
            commitModel(masterBranchPath, insertLock)
            createLock(demoRepoPath, masterBranchPath, demoLockId)

            withTest {
                httpPost("$demoLockPath/query") {
                    setSparqlQueryBody("""
                        ask {
                            <urn:mms:s> <urn:mms:p> <urn:mms:NOT_DEFINED> .
                        }
                    """.trimIndent())
                }.apply {
                    response shouldEqualSparqlResultsJson  """
                        {
                            "head": {},
                            "boolean": false
                        }
                    """.trimIndent()
                }
            }
        }

        "describe lock explicit" {
            commitModel(masterBranchPath, insertLock)
            createLock(demoRepoPath, masterBranchPath, demoLockId)

            withTest {
                httpPost("$demoLockPath/query") {
                    setSparqlQueryBody("""
                        describe <urn:mms:s>
                    """.trimIndent())
                }.apply {
                    response includesTriples {
                        subject("urn:mms:s") {
                            ignoreAll()
                        }
                    }
                }
            }
        }

        "describe lock where" {
            commitModel(masterBranchPath, insertLock)
            createLock(demoRepoPath, masterBranchPath, demoLockId)

            withTest {
                httpPost("$demoLockPath/query") {
                    setSparqlQueryBody("""
                        describe ?s {
                            ?s <urn:mms:p> <urn:mms:o> .
                        }
                    """.trimIndent())
                }.apply {
                    response includesTriples {
                        subject("urn:mms:s") {
                            ignoreAll()
                        }
                    }
                }
            }
        }

        "construct lock" {
            commitModel(masterBranchPath, insertLock)
            createLock(demoRepoPath, masterBranchPath, demoLockId)

            withTest {
                httpPost("$demoLockPath/query") {
                    setSparqlQueryBody("""
                        construct {
                            ?o ?p ?s .
                        } where {
                            ?s ?p ?o .
                        }
                    """.trimIndent())
                }.apply {
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

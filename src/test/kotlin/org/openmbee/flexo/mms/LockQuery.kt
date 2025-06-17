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

class LockQuery : LockAny() {
    init {
        "query lock" {
            testApplication {
                commitModel(masterBranchPath, insertLock)
                createLock(demoRepoPath, masterBranchPath, demoLockId)
                httpPost("$demoLockPath/query") {
                    setSparqlQueryBody("""
                        select ?o {
                            <urn:mms:s> <urn:mms:p> ?o .
                        }
                    """.trimIndent())
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this equalsSparqlResults {
                        binding(
                            "o" to "urn:mms:o".bindingUri
                        )
                    }
                }
            }
        }

        "query lock with graph var" {
            testApplication {
                commitModel(masterBranchPath, insertLock)
                createLock(demoRepoPath, masterBranchPath, demoLockId)
                httpPost("$demoLockPath/query") {
                    setSparqlQueryBody("""
                        select ?g ?o {
                            graph ?g {
                                <urn:mms:s> <urn:mms:p> ?o .
                            }
                        }
                    """.trimIndent())
                }.apply {
                    val graphVal = Json.parseToJsonElement(this.bodyAsText()).jsonObject["results"]!!
                        .jsonObject["bindings"]!!.jsonArray[0].jsonObject["g"]!!.jsonObject["value"]!!
                        .jsonPrimitive.content;

                    graphVal shouldContain "/graphs/Model."

                    this shouldHaveStatus HttpStatusCode.OK
                    this equalsSparqlResults {
                        binding(
                            "g" to graphVal.bindingUri,
                            "o" to "urn:mms:o".bindingUri
                        )
                    }
                }
            }
        }

        "ask lock: true" {
            testApplication {
                commitModel(masterBranchPath, insertLock)
                createLock(demoRepoPath, masterBranchPath, demoLockId)
                httpPost("$demoLockPath/query") {
                    setSparqlQueryBody("""
                        ask {
                            <urn:mms:s> <urn:mms:p> ?o .
                        }
                    """.trimIndent())
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this shouldEqualSparqlResultsJson  """
                        {
                            "head": {},
                            "boolean": true
                        }
                    """.trimIndent()
                }
            }
        }

        "ask lock: false" {
            testApplication {
                commitModel(masterBranchPath, insertLock)
                createLock(demoRepoPath, masterBranchPath, demoLockId)
                httpPost("$demoLockPath/query") {
                    setSparqlQueryBody("""
                        ask {
                            <urn:mms:s> <urn:mms:p> <urn:mms:NOT_DEFINED> .
                        }
                    """.trimIndent())
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this shouldEqualSparqlResultsJson  """
                        {
                            "head": {},
                            "boolean": false
                        }
                    """.trimIndent()
                }
            }
        }

        "describe lock explicit" {
            testApplication {
                commitModel(masterBranchPath, insertLock)
                createLock(demoRepoPath, masterBranchPath, demoLockId)
                httpPost("$demoLockPath/query") {
                    setSparqlQueryBody("""
                        describe <urn:mms:s>
                    """.trimIndent())
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this includesTriples {
                        subject("urn:mms:s") {
                            ignoreAll()
                        }
                    }
                }
            }
        }

        "describe lock where" {
            testApplication {
                commitModel(masterBranchPath, insertLock)
                createLock(demoRepoPath, masterBranchPath, demoLockId)
                httpPost("$demoLockPath/query") {
                    setSparqlQueryBody("""
                        describe ?s {
                            ?s <urn:mms:p> <urn:mms:o> .
                        }
                    """.trimIndent())
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this includesTriples {
                        subject("urn:mms:s") {
                            ignoreAll()
                        }
                    }
                }
            }
        }

        "construct lock" {


            testApplication {
                commitModel(masterBranchPath, insertLock)
                createLock(demoRepoPath, masterBranchPath, demoLockId)
                httpPost("$demoLockPath/query") {
                    setSparqlQueryBody("""
                        construct {
                            ?o ?p ?s .
                        } where {
                            ?s ?p ?o .
                        }
                    """.trimIndent())
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this exclusivelyHasTriples  {
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
            testApplication {
                httpPost("/orgs/not-exists/repos/not-exists/locks/not-exists/query") {
                    setSparqlQueryBody("""
                        select ?o {
                            <urn:mms:s> <urn:mms:p> ?o .
                        }
                    """)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }
    }
}

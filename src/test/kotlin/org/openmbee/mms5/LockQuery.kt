package org.openmbee.mms5

import io.kotest.assertions.json.shouldEqualJson

import io.ktor.http.*
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

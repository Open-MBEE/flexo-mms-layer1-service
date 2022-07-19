package org.openmbee.mms5

import io.kotest.assertions.json.shouldEqualJson
import io.kotest.assertions.ktor.shouldHaveStatus
import io.ktor.http.*
import org.openmbee.mms5.util.*

class LockQuery : LockAny() {
    init {
        "query lock" {
            commitModel(masterPath, """
                insert data {
                    <urn:s> <urn:p> <urn:o> .
                }
            """.trimIndent())

            createLock(repoPath, masterPath, lockId)

            withTest {
                httpPost("$lockPath/query") {
                    setSparqlQueryBody("""
                        select ?o {
                            <urn:s> <urn:p> ?o .
                        }
                    """.trimIndent())
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK

                    response.content!!.shouldEqualJson("""
                        {
                            "head": {
                                "vars": ["o"]
                            },
                            "results": {
                                "bindings": [
                                    {
                                        "o": {
                                            "type": "uri",
                                            "value": "urn:o"
                                        }
                                    }
                                ]
                            }
                        }
                    """.trimIndent())
                }
            }
        }
    }
}
package org.openmbee.flexo.mms

import io.kotest.assertions.ktor.client.shouldHaveStatus
import io.ktor.http.*
import io.ktor.server.testing.*
import org.apache.jena.vocabulary.DCTerms
import org.openmbee.flexo.mms.util.*


class LockWrite : LockAny() {
    init {
        "patch lock with TTL" {
            testApplication {
                createLock(demoRepoPath, masterBranchPath, demoLockId)
                httpPatch(demoLockPath) {
                    setTurtleBody(withAllTestPrefixes("""
                        <> dct:description "foo" .
                    """.trimIndent()))
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this includesTriples {
                        validateLockTriples(demoLockId, null, listOf(
                            DCTerms.description exactly "foo"
                        ))
                    }
                }
            }
        }

        "patch lock with SPARQL UPDATE" {
            testApplication {
                createLock(demoRepoPath, masterBranchPath, demoLockId)
                httpPatch(demoLockPath) {
                    setSparqlUpdateBody(withAllTestPrefixes("""
                        insert data {
                            <> dct:description "foo" .
                        }
                    """.trimIndent()))
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this includesTriples {
                        validateLockTriples(demoLockId, null, listOf(
                            DCTerms.description exactly "foo"
                        ))
                    }
                }
            }
        }
    }
}

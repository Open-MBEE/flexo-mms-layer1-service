package org.openmbee.flexo.mms

import io.ktor.http.*
import org.apache.jena.vocabulary.DCTerms
import org.openmbee.flexo.mms.util.*


class LockWrite : LockAny() {
    init {
        "patch lock with TTL" {
            createLock(demoRepoPath, masterBranchPath, demoLockId)

            withTest {
                httpPatch(demoLockPath) {
                    setTurtleBody(withAllTestPrefixes("""
                        <> dct:description "foo" .
                    """.trimIndent()))
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK

                    response includesTriples {
                        validateLockTriples(demoLockId, null, listOf(
                            DCTerms.description exactly "foo"
                        ))
                    }
                }
            }
        }

        "patch lock with SPARQL UPDATE" {
            createLock(demoRepoPath, masterBranchPath, demoLockId)

            withTest {
                httpPatch(demoLockPath) {
                    setSparqlUpdateBody(withAllTestPrefixes("""
                        insert data {
                            <> dct:description "foo" .
                        }
                    """.trimIndent()))
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK

                    response includesTriples {
                        validateLockTriples(demoLockId, null, listOf(
                            DCTerms.description exactly "foo"
                        ))
                    }
                }
            }
        }
    }
}

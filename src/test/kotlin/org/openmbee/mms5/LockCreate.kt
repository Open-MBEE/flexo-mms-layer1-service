package org.openmbee.mms5

import io.kotest.assertions.ktor.shouldHaveStatus
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.http.*
import io.ktor.server.testing.*
import org.apache.jena.vocabulary.RDF
import org.openmbee.mms5.util.*

class LockCreate : LockAny() {
    fun createAndValidateLock(_lockId: String=lockId, lockBody: String=title(lockName)+fromMaster) {
        withTest {
            httpPut("$repoPath/locks/$_lockId") {
                setTurtleBody(lockBody)
            }.apply {
                response shouldHaveStatus HttpStatusCode.OK

                val etag = response.headers[HttpHeaders.ETag]
                etag.shouldNotBeBlank()

                response.exclusivelyHasTriples {
                    validateLockTriples(lockId, etag!!, orgPath)
                }
            }
        }
    }

    init {
        "reject invalid lock id" {
            withTest {
                httpPut("$lockPath with invalid id") {
                    setTurtleBody(title(lockName)+fromMaster)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.BadRequest
                }
            }
        }

        "create lock on empty master" {
            createAndValidateLock()
        }

        "create lock on committed master" {
            commitModel(masterPath, """
                insert data {
                    <urn:s> <urn:p> <urn:o> .
                }
            """.trimIndent())

            createAndValidateLock()
        }

        "create lock on existing lock" {
            commitModel(masterPath, """
                insert data {
                    <urn:s> <urn:p> <urn:o> .
                }
            """.trimIndent())

            createAndValidateLock()

            createAndValidateLock("other-lock", """
                <> mms:ref <./$lockId> .
            """.trimIndent())
        }
    }
}
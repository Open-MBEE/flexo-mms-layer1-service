package org.openmbee.mms5


import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.http.*
import io.ktor.server.testing.*
import org.apache.jena.rdf.model.Resource
import org.apache.jena.vocabulary.RDF
import org.openmbee.mms5.util.*

class LockCreate : LockAny() {
    fun createAndValidateLock(_lockId: String=lockId, lockBody: String=fromMaster) {
        withTest {
            httpPut("$repoPath/locks/$_lockId") {
                setTurtleBody(lockBody)
            }.apply {
                response shouldHaveStatus HttpStatusCode.OK

                val etag = response.headers[HttpHeaders.ETag]
                etag.shouldNotBeBlank()

                response.exclusivelyHasTriples {
                    modelName = "ValidateLock"
                    validateLockTriples(_lockId, etag!!, orgPath)
                }
            }
        }
    }

    init {
        "reject invalid lock id".config(tags=setOf(NoAuth)) {
            withTest {
                httpPut("$lockPath with invalid id") {
                    setTurtleBody(fromMaster)
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
                    <urn:mms:s> <urn:mms:p> <urn:mms:o> .
                }
            """.trimIndent())

            createAndValidateLock()
        }

        "create lock on existing lock" {
            commitModel(masterPath, """
                insert data {
                    <urn:mms:s> <urn:mms:p> <urn:mms:o> .
                }
            """.trimIndent())

            createAndValidateLock()

            createAndValidateLock("other-lock", "<> mms:ref <./$lockId> .")
        }
    }
}
package org.openmbee.mms5

import io.kotest.assertions.ktor.shouldHaveStatus
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.http.*
import org.apache.jena.vocabulary.RDF
import org.openmbee.mms5.util.*

class LockCreate : LockAny() {
    init {
        "reject invalid lock id" {
            withTest {
                httpPut("$lockPath with invalid id") {
                    setTurtleBody(validLockBodyFromMaster)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.BadRequest
                }
            }
        }

        "create lock on empty master" {
            withTest {
                httpPut(lockPath) {
                    setTurtleBody(validLockBodyFromMaster)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK

                    val etag = response.headers[HttpHeaders.ETag]
                    etag.shouldNotBeBlank()

                    response.exclusivelyHasTriples {
                        validateLockTriples(lockId, etag!!)
                    }
                }
            }
        }

        "create lock on committed master" {
            commitModel(masterPath, """
                insert data {
                    <urn:s> <urn:p> <urn:o> .
                }
            """.trimIndent())

            withTest {
                httpPut(lockPath) {
                    setTurtleBody(validLockBodyFromMaster)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK

                    val etag = response.headers[HttpHeaders.ETag]
                    etag.shouldNotBeBlank()

                    response.exclusivelyHasTriples {
                        validateLockTriples(lockId, etag!!)
                    }
                }
            }
        }
    }
}
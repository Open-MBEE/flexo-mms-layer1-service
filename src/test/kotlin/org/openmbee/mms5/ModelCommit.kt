package org.openmbee.mms5

import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.http.*
import org.openmbee.mms5.util.*

class ModelCommit: ModelAny() {
    init {
        "commit model on master" {
            withTest {
                httpPost("$masterPath/update") {
                    setSparqlUpdateBody(sparqlUpdate)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.Created

                    val etag = response.headers[HttpHeaders.ETag]
                    etag.shouldNotBeBlank()

                    response exclusivelyHasTriples {
                        validateModelCommitResponse(masterPath, etag!!)
                    }
                }
            }
        }

        "commit model on branch" {
            val branch = createBranch(repoPath, "master", branchId, branchName)

            withTest {
                httpPost("$branchPath/update") {
                    setSparqlUpdateBody(sparqlUpdate)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.Created

                    val etag = response.headers[HttpHeaders.ETag]
                    etag.shouldNotBeBlank()

                    response exclusivelyHasTriples {
                        validateModelCommitResponse(branchPath, etag!!)
                    }
                }
            }
        }
    }
}

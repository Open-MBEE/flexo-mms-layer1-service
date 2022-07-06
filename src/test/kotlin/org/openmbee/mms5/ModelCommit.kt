package org.openmbee.mms5

import io.kotest.assertions.ktor.shouldHaveStatus
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
                    response.headers[HttpHeaders.ETag].shouldNotBeBlank()
                    val etag = response.headers[HttpHeaders.ETag]
                    response exclusivelyHasTriples {
                        modelName = "response"
                        validateModelCommitResponse(masterPath, etag!!, repoEtag)
                    }
                }
            }
        }
        "commit model on branch" {
            val branch = createBranch(branchId, branchName, "master", repoId, orgId)
            withTest {
                httpPost("$branchPath/update") {
                    setSparqlUpdateBody(sparqlUpdate)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.Created
                    response.headers[HttpHeaders.ETag].shouldNotBeBlank()
                    val etag = response.headers[HttpHeaders.ETag]
                    response exclusivelyHasTriples {
                        modelName = "response"
                        validateModelCommitResponse(branchPath, etag!!, repoEtag)
                    }
                }
            }
        }
    }
}

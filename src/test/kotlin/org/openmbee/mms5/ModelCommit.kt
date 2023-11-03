package org.openmbee.mms5

import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.http.*
import org.openmbee.mms5.util.*

fun ModelCommit.commitAndValidateModel(branchPath: String) {
    withTest {
        httpPost("$branchPath/update") {
            setSparqlUpdateBody(insertAliceRex)
        }.apply {
            val etag = response.headers[HttpHeaders.ETag]
            etag.shouldNotBeBlank()

            response.exclusivelyHasTriples(HttpStatusCode.Created) {
                validateModelCommitResponse(branchPath, etag!!)
            }
        }
    }
}

class ModelCommit: ModelAny() {
    init {
        "commit model on master" {
            commitAndValidateModel(masterPath)
        }

        "commit model on empty master" {
            val branch = createBranch(repoPath, "master", branchId, branchName)

            commitAndValidateModel(branchPath)
        }

        "commit model on non-empty master" {
            commitModel(masterPath, insertAliceRex)

            val branch = createBranch(repoPath, "master", branchId, branchName)

            commitAndValidateModel(branchPath)
        }
    }
}

package org.openmbee.flexo.mms

import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.http.*
import org.openmbee.flexo.mms.util.*

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
            commitAndValidateModel(masterBranchPath)
        }

        "commit model on empty master" {
            val branch = createBranch(demoRepoPath, "master", demoBranchId, demoBranchName)

            commitAndValidateModel(demoBranchPath)
        }

        "commit model on non-empty master" {
            commitModel(masterBranchPath, insertAliceRex)

            val branch = createBranch(demoRepoPath, "master", demoBranchId, demoBranchName)

            commitAndValidateModel(demoBranchPath)
        }
    }
}

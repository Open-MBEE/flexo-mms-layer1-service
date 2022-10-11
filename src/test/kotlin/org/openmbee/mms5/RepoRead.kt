package org.openmbee.mms5



import io.kotest.matchers.string.shouldNotBeEmpty
import io.ktor.http.*
import org.openmbee.mms5.util.*

class RepoRead : RepoAny() {
    init {
        "head non-existent repo" {
            withTest {
                httpHead(repoPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }

        "get non-existent repo" {
            withTest {
                httpGet(repoPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }

        "head repo" {
            val create = createRepo(orgPath, repoId, repoName)

            withTest {
                httpHead(repoPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response.headers[HttpHeaders.ETag].shouldNotBeEmpty()
                    // response.shouldHaveHeader(HttpHeaders.ETag, create.response.headers[HttpHeaders.ETag]!!)
                }
            }
        }

        "get repo" {
            val create = createRepo(orgPath, repoId, repoName)

            withTest {
                httpGet(repoPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response.headers[HttpHeaders.ETag].shouldNotBeEmpty()
                    // response.shouldHaveHeader(HttpHeaders.ETag, create.response.headers[HttpHeaders.ETag]!!)

                    response exclusivelyHasTriples {
                        validateRepoTriplesWithMasterBranch(repoId, repoName, orgPath)
                    }
                }
            }
        }
    }
}

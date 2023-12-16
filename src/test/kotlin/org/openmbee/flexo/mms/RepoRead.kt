package org.openmbee.flexo.mms



import io.kotest.matchers.string.shouldNotBeEmpty
import io.ktor.http.*
import org.openmbee.flexo.mms.util.*

class RepoRead : RepoAny() {
    init {
        "head non-existent repo" {
            withTest {
                httpHead(demoRepoPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }

        "get non-existent repo" {
            withTest {
                httpGet(demoRepoPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }

        "head repo" {
            val create = createRepo(demoOrgPath, demoRepoId, demoRepoName)

            withTest {
                httpHead(demoRepoPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response.headers[HttpHeaders.ETag].shouldNotBeEmpty()
                    // response.shouldHaveHeader(HttpHeaders.ETag, create.response.headers[HttpHeaders.ETag]!!)
                }
            }
        }

        "get repo" {
            val create = createRepo(demoOrgPath, demoRepoId, demoRepoName)

            withTest {
                httpGet(demoRepoPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response.headers[HttpHeaders.ETag].shouldNotBeEmpty()
                    // response.shouldHaveHeader(HttpHeaders.ETag, create.response.headers[HttpHeaders.ETag]!!)

                    response exclusivelyHasTriples {
                        validateRepoTriplesWithMasterBranch(demoRepoId, demoRepoName, demoOrgPath)
                    }
                }
            }
        }

        "get all repos" {
            val createBase = createRepo(demoOrgPath, demoRepoId, demoRepoName)
            val createFoo = createRepo(demoOrgPath, fooRepoId, fooRepoName)
            val createBar = createRepo(demoOrgPath, barRepoId, barRepoName)

            withTest {
                httpGet("$demoOrgPath/repos") {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK

                    logger.info(response.content)

                    response.includesTriples {
                        validateRepoTriplesWithMasterBranch(demoRepoId, demoRepoName, demoOrgPath)
                    }
                }
            }
        }
    }
}

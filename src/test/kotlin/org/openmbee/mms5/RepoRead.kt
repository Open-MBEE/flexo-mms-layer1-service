package org.openmbee.mms5

import io.kotest.assertions.ktor.shouldHaveHeader
import io.kotest.assertions.ktor.shouldHaveStatus
import io.kotest.core.test.TestCase
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.http.*
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.mms5.util.*
import org.slf4j.LoggerFactory

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
            val create = createRepo(repoId, repoName, orgId)

            withTest {
                httpHead(repoPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response.shouldHaveHeader(HttpHeaders.ETag, create.response.headers[HttpHeaders.ETag]!!)
                }
            }
        }

        "get repo" {
            val create = createRepo(repoId, repoName, orgId)

            withTest {
                httpGet(repoPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response.shouldHaveHeader(HttpHeaders.ETag, create.response.headers[HttpHeaders.ETag]!!)

                    response exclusivelyHasTriples {
                        validateRepoTriples(response, repoId, repoName, orgPath)
                    }
                }
            }
        }
    }
}
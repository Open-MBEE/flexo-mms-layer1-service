package org.openmbee.mms5

import io.kotest.assertions.ktor.shouldHaveHeader
import io.kotest.assertions.ktor.shouldHaveStatus
import io.ktor.http.*
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.mms5.util.*

class BranchRead : RefAny() {
    init {
        "head non-existent branch" {
            withTest {
                httpHead(branchPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }

        "get non-existent branch" {
            withTest {
                httpGet(branchPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }
        "create and head new branch" {
            //val update = updateModel("""
            //    insert { <somesub> <somepred> 5 .}
            //""".trimIndent(), "master", repoId, orgId)
            val create = createBranch(repoPath, "master", branchId, branchName)

            withTest {
                httpHead(branchPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response.shouldHaveHeader(HttpHeaders.ETag, create.response.headers[HttpHeaders.ETag]!!)
                }
            }
        }

        "get master branch" {
            withTest {
                httpGet(masterPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    //response.shouldHaveHeader(HttpHeaders.ETag, create.response.headers[HttpHeaders.ETag]!!)
                    response exclusivelyHasTriples {
                        val branchiri = localIri(masterPath)

                        subject(branchiri) {
                            includes(
                                RDF.type exactly MMS.Branch,
                                MMS.id exactly "master",
                                DCTerms.title exactly branchName.en,
                            )
                        }
                    }
                }
            }
        }

        "create from master then get all branches" {
            val update = commitModel(masterPath, """
                insert { 
                    <http://somesub> <http://somepred> 5 .
                }
            """.trimIndent())
            val create = createBranch(repoPath, "master", branchId, branchName)
            withTest {
                httpGet("/orgs/$orgId/repos/$repoId/branches") {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response exclusivelyHasTriples {
                        subject(localIri(branchPath)) {
                            includes(
                                RDF.type exactly MMS.Branch,
                                MMS.id exactly branchId,
                                DCTerms.title exactly branchName.en,
                                MMS.etag exactly create.response.headers[HttpHeaders.ETag]!!,
                            )
                        }
                        subject(localIri(masterPath)) {
                            includes(
                                RDF.type exactly MMS.Branch,
                                MMS.id exactly "master",
                                DCTerms.title exactly "master"
                            )
                        }
                    }
                }
            }
        }
    }
}

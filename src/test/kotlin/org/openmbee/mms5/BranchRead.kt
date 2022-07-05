package org.openmbee.mms5

import io.kotest.assertions.ktor.shouldHaveHeader
import io.kotest.assertions.ktor.shouldHaveStatus
import io.ktor.http.*
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.mms5.util.*

class BranchRead : BranchAny() {
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
/* not working
        "create and head new branch" {
            //val update = updateModel("""
            //    insert { <somesub> <somepred> 5 .}
            //""".trimIndent(), "master", repoId, orgId)
            val create = createBranch(branchId, branchName, "master", repoId, orgId)

            withTest {
                httpHead(branchPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response.shouldHaveHeader(HttpHeaders.ETag, create.response.headers[HttpHeaders.ETag]!!)
                }
            }
        }
*/
        /* weird response
        "get master branch" {
            withTest {
                httpGet(masterPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    //response.shouldHaveHeader(HttpHeaders.ETag, create.response.headers[HttpHeaders.ETag]!!)
                    response exclusivelyHasTriples {
                        val branchiri = localIri(masterPath)

                        subject(branchiri) {
                            exclusivelyHas(
                                RDF.type exactly MMS.Branch,
                                MMS.id exactly branchId,
                                DCTerms.title exactly branchName.en,
                            )
                        }
                    }
                }
            }
        }*/

        "create from master then get all branches" {
            val update = updateModel("""
                insert { <http:somesub> <http:somepred> 5 .}
            """.trimIndent(), "master", repoId, orgId)
            val create = createBranch(branchId, branchName, "master", repoId, orgId)
            withTest {
                httpGet("/orgs/$orgId/repos/$repoId/branches") {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response exclusivelyHasTriples {
                        subject(localIri(branchPath)) {
                            exclusivelyHas(
                                RDF.type exactly MMS.Branch,
                                MMS.id exactly branchId,
                                DCTerms.title exactly branchName.en,
                                MMS.etag exactly create.response.headers[HttpHeaders.ETag]!!,
                            )
                        }
                        subject(localIri(masterPath)) {
                            exclusivelyHas(
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
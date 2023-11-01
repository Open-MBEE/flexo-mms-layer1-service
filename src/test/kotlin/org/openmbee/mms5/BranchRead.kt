package org.openmbee.mms5



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

                    response includesTriples {
                        val branchiri = localIri(masterPath)

                        subject(branchiri) {
                            includes(
                                RDF.type exactly MMS.Branch,
                                MMS.id exactly "master",
                                DCTerms.title exactly "Master".en,
                            )
                        }
                    }
                }
            }
        }

        "create from committed master then get all branches" {
            val update = commitModel(masterPath, """
                insert data { 
                    <urn:mms:s> <urn:mms:p> 5 .
                }
            """.trimIndent())

            val create = createBranch(repoPath, "master", branchId, branchName)

            withTest {
                httpGet("$repoPath/branches") {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK

                    response includesTriples  {
                        subject(localIri(branchPath)) {
                            includes(
                                RDF.type exactly MMS.Branch,
                                MMS.id exactly branchId,
                                DCTerms.title exactly branchName.en,
                                MMS.etag startsWith "",
                            )
                        }

                        subject(localIri(masterPath)) {
                            includes(
                                RDF.type exactly MMS.Branch,
                                MMS.id exactly "master",
                                DCTerms.title exactly "Master".en
                            )
                        }
                    }
                }
            }
        }
    }
}

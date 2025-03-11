package org.openmbee.flexo.mms



import io.ktor.http.*
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.util.*

class BranchRead : RefAny() {
    init {
        "head non-existent branch" {
            withTest {
                httpHead(demoBranchPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }

        "get non-existent branch" {
            withTest {
                httpGet(demoBranchPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }

        "create and head new branch" {
            val create = createBranch(demoRepoPath, "master", demoBranchId, demoBranchName)

            withTest {
                httpHead(demoBranchPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.NoContent

                    response.shouldHaveHeader(HttpHeaders.ETag, create.response.headers[HttpHeaders.ETag]!!)
                }
            }
        }

        "get master branch" {
            withTest {
                httpGet(masterBranchPath) {}.apply {
                    response includesTriples {
                        val branchiri = localIri(masterBranchPath)

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
            val update = commitModel(masterBranchPath, """
                insert data { 
                    <urn:mms:s> <urn:mms:p> 5 .
                }
            """.trimIndent())

            val create = createBranch(demoRepoPath, "master", demoBranchId, demoBranchName)

            withTest {
                httpGet("$demoRepoPath/branches") {}.apply {
                    response includesTriples  {
                        subject(localIri(demoBranchPath)) {
                            includes(
                                RDF.type exactly MMS.Branch,
                                MMS.id exactly demoBranchId,
                                DCTerms.title exactly demoBranchName.en,
                                MMS.etag startsWith "",
                                MMS.created startsWith ""
                            )
                        }

                        subject(localIri(masterBranchPath)) {
                            includes(
                                RDF.type exactly MMS.Branch,
                                MMS.id exactly "master",
                                DCTerms.title exactly "Master".en,
                                MMS.created startsWith ""
                            )
                        }
                    }
                }
            }
        }
    }
}

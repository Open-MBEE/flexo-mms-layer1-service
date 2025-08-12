package org.openmbee.flexo.mms



import io.kotest.assertions.ktor.client.shouldHaveHeader
import io.kotest.assertions.ktor.client.shouldHaveStatus
import io.ktor.http.*
import io.ktor.server.testing.*
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.util.*

class BranchRead : RefAny() {
    init {
        "head non-existent branch" {
            testApplication {
                httpHead(demoBranchPath) {}.apply {
                    this shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }

        "get non-existent branch" {
            testApplication {
                httpGet(demoBranchPath) {}.apply {
                    this shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }

        "create and head new branch" {
            testApplication {
                val create = createBranch(demoRepoPath, "master", demoBranchId, demoBranchName)
                httpHead(demoBranchPath) {}.apply {
                    this shouldHaveStatus HttpStatusCode.NoContent
                    this.shouldHaveHeader(HttpHeaders.ETag, create.headers[HttpHeaders.ETag]!!)
                }
            }
        }

        "get master branch" {
            testApplication {
                httpGet(masterBranchPath) {}.apply {
                    this includesTriples {
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
            testApplication {
                val update = commitModel(masterBranchPath, """
                    insert data { 
                        <urn:mms:s> <urn:mms:p> 5 .
                    }
                    """.trimIndent())
                val create = createBranch(demoRepoPath, "master", demoBranchId, demoBranchName)
                httpGet("$demoRepoPath/branches") {}.apply {
                    this includesTriples  {
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

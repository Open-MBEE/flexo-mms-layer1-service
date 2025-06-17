package org.openmbee.flexo.mms

import io.kotest.assertions.ktor.client.shouldHaveStatus
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.apache.jena.rdf.model.ResourceFactory
import org.openmbee.flexo.mms.util.*

class ModelCommit: ModelAny() {
    suspend fun HttpResponse.validateCommitResult(branchPath: String) {
        this shouldHaveStatus HttpStatusCode.Created
        val etag = this.headers[HttpHeaders.ETag]
        etag.shouldNotBeBlank()
        this.exclusivelyHasTriples {
            validateModelCommitResponse(branchPath, etag!!)
        }
    }
    init {
        "insert data on master" {
            val updateBody = mutableListOf(insertAliceRex, insertBobFluffy).joinToString(";\n")
            testApplication {
                httpPost("$masterBranchPath/update") {
                    setSparqlUpdateBody(updateBody)
                }.apply {
                    validateCommitResult(masterBranchPath)
                }

                httpGet("$masterBranchPath/graph") {}.apply {
                    this.exclusivelyHasTriples {
                        val people = demoPrefixes.get("")
                        subject("${people}Alice") {
                            ignoreAll()
                        }
                        subject("${people}Rex") {
                            ignoreAll()
                        }
                        subject("${people}Bob") {
                            ignoreAll()
                        }
                        subject("${people}Fluffy") {
                            ignoreAll()
                        }
                    }
                }
            }

        }

        "commit model with various operations" {
            // resulting graph should have:
            // <urn:that> <urn:gets> <urn:reinserted>
            // <urn:these> <urn:more> <urn:inserts>
            val updateBody = mutableListOf(
                """
                    insert data {
                        <urn:this> <urn:is> <urn:inserted> .
                        <urn:that> <urn:willbe> <urn:deleted> .
                    }
                """.trimIndent(),
                """
                    delete where {
                        <urn:that> ?p ?o .
                    }
                """.trimIndent(),
                """
                    delete data {
                        <urn:does> <urn:not> <urn:exist> .
                    }
                """.trimIndent(),
                """
                    insert data {             
                         <urn:that> <urn:gets> <urn:reinserted>
                    }                   
                """.trimIndent(),
                """
                    delete {
                        ?s ?p ?o .
                    } insert {
                        <urn:these> <urn:more> <urn:inserts>
                    } where {
                        optional {
                            ?s ?p ?o .
                        }
                        values ?s { <urn:this> }
                    }
                """.trimIndent()
            ).joinToString(";\n")
            testApplication {
                httpPost("$masterBranchPath/update") {
                    setSparqlUpdateBody(updateBody)
                }.apply {
                    validateCommitResult(masterBranchPath)
                }

                httpGet("$masterBranchPath/graph") {}.apply {
                    this.exclusivelyHasTriples {
                        subject("urn:these") {
                            exclusivelyHas(ResourceFactory.createProperty("urn:more") exactly ResourceFactory.createResource("urn:inserts"))
                        }
                        subject("urn:that") {
                            exclusivelyHas(ResourceFactory.createProperty("urn:gets") exactly ResourceFactory.createResource("urn:reinserted"))
                        }
                    }
                }
            }
        }

        "commit model on non-empty branch" {
            val updateBody = """
                insert data {
                    <urn:this> <urn:is> <urn:inserted> .
                }
            """.trimIndent()
            testApplication {
                commitModel(masterBranchPath, insertAliceRex)
                createBranch(demoRepoPath, "master", demoBranchId, demoBranchName)
                httpPost("$demoBranchPath/update") {
                    setSparqlUpdateBody(updateBody)
                }.apply {
                    validateCommitResult(demoBranchPath)
                }
         
                httpGet("$demoBranchPath/graph") {}.apply {
                    this.includesTriples {
                        subject("urn:this") {
                            exclusivelyHas(ResourceFactory.createProperty("urn:is") exactly ResourceFactory.createResource("urn:inserted"))
                        }
                    }
                }
            }
        }

        "model commit conflict" {
            //manually add a transaction into backend
            val updateUrl = backend.getUpdateUrl()
            addDummyTransaction(updateUrl, masterBranchPath)
            testApplication {
                httpPost("$masterBranchPath/update") {
                    setSparqlUpdateBody("""insert data {<urn:this> <urn:is> <urn:inserted> .}""")
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Conflict
                }
            }
        }

        "where optional clause doesn't match" {
            val updateBody = """
                delete {
                    ?s ?p ?o .
                } where {
                    optional {
                        ?s ?p ?o .
                    }
                    values ?s { <urn:this> }
                };
                insert data {
                    <urn:this> <urn:is> <urn:inserted> .
                }
            """.trimIndent()
            testApplication {
                httpPost("$masterBranchPath/update") {
                    setSparqlUpdateBody(updateBody)
                }.apply {
                    validateCommitResult(masterBranchPath)
                }

                httpGet("$masterBranchPath/graph") {}.apply {
                    this.includesTriples {
                        subject("urn:this") {
                            exclusivelyHas(ResourceFactory.createProperty("urn:is") exactly ResourceFactory.createResource("urn:inserted"))
                        }
                    }
                }
            }
        }
    }
}

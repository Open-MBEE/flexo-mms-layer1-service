package org.openmbee.flexo.mms

import io.kotest.assertions.ktor.client.shouldHaveStatus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.apache.jena.vocabulary.XSD
import org.openmbee.flexo.mms.util.*

class ModelLoad : ModelAny() {
    suspend fun HttpResponse.validateModelLoadResponse() {
        this shouldHaveStatus HttpStatusCode.OK
        val commit = this.headers[HttpHeaders.Location]
        commit.shouldNotBeBlank()
        val etag = this.headers[HttpHeaders.ETag]
        etag.shouldNotBeBlank()
        this includesTriples {
            subject(commit!!) {
                includes(
                    RDF.type exactly MMS.Commit,
                    MMS.submitted hasDatatype XSD.dateTime,
                    MMS.parent startsWith localIri("$demoCommitsPath/").iri,
                    MMS.data startsWith localIri("$demoCommitsPath/").iri,
                    MMS.createdBy exactly localIri("/users/root").iri
                )
            }
        }
    }

    fun HttpResponse.validateModelLoadNoChangeResponse() {
        this shouldHaveStatus HttpStatusCode.OK
        val etag = this.headers[HttpHeaders.ETag]
        etag.shouldNotBeBlank()
    }

    init {
        "load all inserts on empty model" {
            testApplication {
                httpPut("$masterBranchPath/graph") {
                    setTurtleBody(loadAliceRex)
                }.apply {
                    validateModelLoadResponse()
                }
            }
        }

        "load no change on empty model" {
            testApplication {
                httpPut("$masterBranchPath/graph") {
                    setTurtleBody("")
                }.apply {
                    validateModelLoadNoChangeResponse()
                }
            }
        }

        "load all inserts on non-empty model" {
            testApplication {
                loadModel(masterBranchPath, loadAliceRex)
                httpPut("$masterBranchPath/graph") {
                    setTurtleBody("""
                        $loadAliceRex

                        :Xavier a :Person ;
                            foaf:name "Xavier" .
                    """.trimIndent())
                }.apply {
                    validateModelLoadResponse()
                }
            }
        }

        "load all deletes on non-empty model" {
            testApplication {
                loadModel(masterBranchPath, loadAliceRex)
                httpPut("$masterBranchPath/graph") {
                    setTurtleBody("")
                }.apply {
                    validateModelLoadResponse()
                }
            }
        }

        "load no change on non-empty model" {
            testApplication {
                loadModel(masterBranchPath, loadAliceRex)
                httpPut("$masterBranchPath/graph") {
                    setTurtleBody(loadAliceRex)
                }.apply {
                    validateModelLoadNoChangeResponse()
                }
            }
        }

        "load both inserts and deletes on non-empty model" {
            testApplication {
                loadModel(masterBranchPath, loadAliceRex)
                httpPut("$masterBranchPath/graph") {
                    setTurtleBody("""
                        @prefix : <https://mms.openmbee.org/demos/people/>
                        @prefix foaf: <http://xmlns.com/foaf/0.1/>

                        :Xavier a :Person ;
                            foaf:name "Xavier" .
                    """.trimIndent())
                }.apply {
                    validateModelLoadResponse()
                }
            }
        }

        "model load conflict" {
            //manually add a transaction into backend
            val updateUrl = backend.getUpdateUrl()
            addDummyTransaction(updateUrl, masterBranchPath)
            testApplication {
                httpPut("$masterBranchPath/graph") {
                    setTurtleBody(loadAliceRex)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Conflict
                }
            }
        }

        "lock graph rejects other methods" {
            testApplication {
                commitModel(masterBranchPath, insertAliceRex)
                createLock(demoRepoPath, masterBranchPath, demoLockId)
                onlyAllowsMethods("$demoLockPath/graph", setOf(
                    HttpMethod.Head,
                    HttpMethod.Get,
                ))
            }
        }

        "head branch graph" {
            testApplication {
                commitModel(masterBranchPath, insertAliceRex)
                httpHead("$masterBranchPath/graph") {}.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                }
            }
        }

        "get branch graph" {
            testApplication {
                commitModel(masterBranchPath, insertAliceRex)
                httpGet("$masterBranchPath/graph") {}.apply {
                    this shouldHaveStatus HttpStatusCode.OK

                    this.exclusivelyHasTriples {
                        subjectTerse(":Alice") {
                            ignoreAll()
                        }

                        subjectTerse(":Rex") {
                            ignoreAll()
                        }
                    }
                }
            }
        }

        "head lock graph" {
            testApplication {
                commitModel(masterBranchPath, insertAliceRex)
                createLock(demoRepoPath, masterBranchPath, demoLockId)
                httpHead("$demoLockPath/graph") {}.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this.bodyAsText() shouldBe ""
                }
            }
        }

        "get lock graph" {
            testApplication {
                commitModel(masterBranchPath, insertAliceRex)
                createLock(demoRepoPath, masterBranchPath, demoLockId)
                httpGet("$demoLockPath/graph") {}.apply {
                    this shouldHaveStatus HttpStatusCode.OK

                    this.exclusivelyHasTriples {
                        subjectTerse(":Alice") {
                            ignoreAll()
                        }

                        subjectTerse(":Rex") {
                            ignoreAll()
                        }
                    }
                }
            }
        }
    }
}

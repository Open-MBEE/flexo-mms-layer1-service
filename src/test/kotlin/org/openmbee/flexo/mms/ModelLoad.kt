package org.openmbee.flexo.mms

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldStartWith
import io.ktor.http.*
import io.ktor.server.testing.*
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.apache.jena.vocabulary.XSD
import org.openmbee.flexo.mms.util.*

class ModelLoad : ModelAny() {
    fun TestApplicationCall.validateModelLoadResponse() {
        response shouldHaveStatus HttpStatusCode.OK
        val commit = response.headers[HttpHeaders.Location]
        commit.shouldNotBeBlank()
        val etag = response.headers[HttpHeaders.ETag]
        etag.shouldNotBeBlank()
        response includesTriples {
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

    fun TestApplicationCall.validateModelLoadNoChangeResponse() {
        response shouldHaveStatus HttpStatusCode.OK
        val etag = response.headers[HttpHeaders.ETag]
        etag.shouldNotBeBlank()
    }

    init {
        "load all inserts on empty model" {
            withTest {
                httpPut("$masterBranchPath/graph") {
                    setTurtleBody(loadAliceRex)
                }.apply {
                    validateModelLoadResponse()
                }
            }
        }

        "load no change on empty model" {
            withTest {
                httpPut("$masterBranchPath/graph") {
                    setTurtleBody("")
                }.apply {
                    validateModelLoadNoChangeResponse()
                }
            }
        }

        "load all inserts on non-empty model" {
            loadModel(masterBranchPath, loadAliceRex)

            withTest {
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
            loadModel(masterBranchPath, loadAliceRex)

            withTest {
                httpPut("$masterBranchPath/graph") {
                    setTurtleBody("")
                }.apply {
                    validateModelLoadResponse()
                }
            }
        }

        "load no change on non-empty model" {
            loadModel(masterBranchPath, loadAliceRex)

            withTest {
                httpPut("$masterBranchPath/graph") {
                    setTurtleBody(loadAliceRex)
                }.apply {
                    validateModelLoadNoChangeResponse()
                }
            }
        }

        "load both inserts and deletes on non-empty model" {
            loadModel(masterBranchPath, loadAliceRex)

            withTest {
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

        "lock graph rejects other methods" {
            commitModel(masterBranchPath, insertAliceRex)
            createLock(demoRepoPath, masterBranchPath, demoLockId)

            withTest {
                onlyAllowsMethods("$demoLockPath/graph", setOf(
                    HttpMethod.Head,
                    HttpMethod.Get,
                ))
            }
        }

        "head branch graph" {
            commitModel(masterBranchPath, insertAliceRex)

            withTest {
                httpHead("$masterBranchPath/graph") {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK
//                    response.content shouldBe null
                }
            }
        }

        "get branch graph" {
            commitModel(masterBranchPath, insertAliceRex)

            withTest {
                httpGet("$masterBranchPath/graph") {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK

                    response.exclusivelyHasTriples {
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
            commitModel(masterBranchPath, insertAliceRex)
            createLock(demoRepoPath, masterBranchPath, demoLockId)

            withTest {
                httpHead("$demoLockPath/graph") {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response.content shouldBe null
                }
            }
        }

        "get lock graph" {
            commitModel(masterBranchPath, insertAliceRex)
            createLock(demoRepoPath, masterBranchPath, demoLockId)

            withTest {
                httpGet("$demoLockPath/graph") {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK

                    response.exclusivelyHasTriples {
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

package org.openmbee.flexo.mms

import io.kotest.assertions.json.shouldBeJsonObject
import io.kotest.assertions.json.shouldContainJsonKey
import io.kotest.assertions.json.shouldContainJsonKeyValue
import io.kotest.assertions.ktor.client.shouldHaveStatus
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.util.*

class RepoQuery : ModelAny() {

    val lockCommitQuery = """
        select ?time where {
            ?lock mms:id "$demoLockId" ;
                mms:commit ?commit ;
                .

            ?commit mms:submitted ?time .
        }
    """.trimIndent()

    init {
        "query time of commit of lock" {
            testApplication {
                val update = commitModel(masterBranchPath, insertAliceRex)
//            val etag = update.response.headers[HttpHeaders.ETag]!!
                createLock(demoRepoPath, masterBranchPath, demoLockId)
                // lock should be pointing to the commit from update
                httpPost("$demoRepoPath/query") {
                    setSparqlQueryBody(withAllTestPrefixes(lockCommitQuery))
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this.headers["Content-Type"] shouldStartWith "application/sparql-results+json"
                    this.bodyAsText().shouldBeJsonObject()
//                    response.content!!.shouldContainJsonKeyValue("$.results.bindings[0].etag.value", etag)
                    this.bodyAsText().shouldContainJsonKey("$.results.bindings[0].time.value")
                }
            }
        }

        "nothing exists" {
            testApplication {
                httpPost("/orgs/not-exists/repos/not-exists/query") {
                    setSparqlQueryBody("""
                        select ?o {
                            <urn:mms:s> <urn:mms:p> ?o .
                        }
                    """)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }

        "user query with BASE" {
            // lock should be pointing to the commit from update
            testApplication {
                httpPost("$demoRepoPath/query") {
                    setSparqlQueryBody("""
                        BASE <hacked>

                        ${withAllTestPrefixes(lockCommitQuery)}
                    """.trimIndent())
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                }
            }
        }

        "get repo metadata graph" {
            testApplication {
                httpGet("$demoRepoPath/graph") {}.apply {
                    this shouldHaveStatus HttpStatusCode.OK
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

        "repo metadata graph only allow head and get" {
            testApplication {
                onlyAllowsMethods("$demoRepoPath/graph", setOf(
                    HttpMethod.Head,
                    HttpMethod.Get
                ))
            }
        }
    }
}

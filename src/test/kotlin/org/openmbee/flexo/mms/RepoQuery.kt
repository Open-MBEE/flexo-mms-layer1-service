package org.openmbee.flexo.mms

import io.kotest.assertions.json.shouldBeJsonObject
import io.kotest.assertions.json.shouldContainJsonKey
import io.kotest.assertions.json.shouldContainJsonKeyValue
import io.kotest.matchers.string.shouldStartWith
import io.ktor.http.*
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
            val update = commitModel(masterBranchPath, insertAliceRex)
//            val etag = update.response.headers[HttpHeaders.ETag]!!
            createLock(demoRepoPath, masterBranchPath, demoLockId)
            // lock should be pointing to the commit from update
            withTest {
                httpPost("$demoRepoPath/query") {
                    setSparqlQueryBody(withAllTestPrefixes(lockCommitQuery))
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response.headers["Content-Type"] shouldStartWith "application/sparql-results+json"
                    response.content!!.shouldBeJsonObject()
//                    response.content!!.shouldContainJsonKeyValue("$.results.bindings[0].etag.value", etag)
                    response.content!!.shouldContainJsonKey("$.results.bindings[0].time.value")
                }
            }
        }

        "nothing exists" {
            withTest {
                httpPost("/orgs/not-exists/repos/not-exists/query") {
                    setSparqlQueryBody("""
                        select ?o {
                            <urn:mms:s> <urn:mms:p> ?o .
                        }
                    """)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }

        "user query with BASE" {
            // lock should be pointing to the commit from update
            withTest {
                httpPost("$demoRepoPath/query") {
                    setSparqlQueryBody("""
                        BASE <hacked>

                        ${withAllTestPrefixes(lockCommitQuery)}
                    """.trimIndent())
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                }
            }
        }
    }
}

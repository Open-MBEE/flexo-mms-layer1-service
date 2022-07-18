package org.openmbee.mms5

import io.kotest.assertions.json.shouldBeJsonObject
import io.kotest.assertions.json.shouldContainJsonKey
import io.kotest.assertions.json.shouldContainJsonKeyValue
import io.kotest.assertions.ktor.shouldHaveHeader
import io.kotest.assertions.ktor.shouldHaveStatus
import io.ktor.http.*
import org.openmbee.mms5.util.*

class RepoQuery : ModelAny() {

    val lockCommitQuery = """
        select ?etag ?time where {
            ?lock mms:id "$lockId" .
            ?lock mms:commit ?commit .
            ?commit mms:etag ?etag .
            ?commit mms:submitted ?time .
        }
    """.trimIndent()

    init {
        "query time of commit of lock" {
            val update = commitModel(masterPath, sparqlUpdate)
            val etag = update.response.headers[HttpHeaders.ETag]!!
            createLock(repoPath, "branches/master", lockId)
            // lock should be pointing to the commit from update
            withTest {
                httpPost("$repoPath/query") {
                    setSparqlQueryBody(lockCommitQuery)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response.shouldHaveHeader("Content-Type", "application/sparql-results+json")
                    response.content!!.shouldBeJsonObject()
                    response.content!!.shouldContainJsonKeyValue("$.results.bindings[0].etag.value", etag)
                    response.content!!.shouldContainJsonKey("$.results.bindings[0].time.value")
                }
            }
        }
    }
}

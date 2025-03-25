package org.openmbee.flexo.mms

import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.http.*
import org.openmbee.flexo.mms.util.*

class ScratchLoadSetup: ScratchAny() {
    init {
        "setup scratch in repo" {
            withTest {
                httpPost("$demoScratchPath/update") {
                    setSparqlUpdateBody(insertAliceRex)
                }.apply {
                    // Include etag since repos have them - want to be sure not concurrently modifying
                    val etag = response.headers[HttpHeaders.ETag]
                    etag.shouldNotBeBlank()

                    response shouldHaveStatus HttpStatusCode.Created
                    // Not convinced I need this - checks commit info and repo doesn't even have this file
//                    response.exclusivelyHasTriples {
//                        validateModelCommitResponse(branchPath, etag!!)
//                    }
                }
            }
        }
    }
}

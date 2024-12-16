package org.openmbee.flexo.mms

import io.ktor.http.*
import io.ktor.server.testing.*
import org.openmbee.flexo.mms.util.*
import org.slf4j.LoggerFactory


open class ArtifactAny : RefAny() {
    override val logger = LoggerFactory.getLogger(LockAny::class.java)

    val artifactsPath = "$demoRepoPath/artifacts"   //orgs/open-mbee/repos/new-repo/artifacts


    init {
        "post artifact text/plain" {
            withTest {
                httpPost("$artifactsPath/store") {
                    addHeader("Content-Type", "text/plain")
                    setBody("foo")
                }.apply {
                    response shouldHaveStatus HttpStatusCode.Created
//                    response.headers["Link"] shouldMatch "artifactsPath".toRegex()
//                    response.contentType() shouldBe ContentType.Text.Plain
//                    response shouldHaveContent "foo"
                }
            }
        }

        /*
        Need tests for more posting a specific artifact in different formats, getting all artifacts,
        getting a specific artifact given an id (? location?)
        And try to create something on a repo level scope - just get rid of
         */

        "post artifact png image" {
            withTest{
                httpPost("$artifactsPath/store") {
                    addHeader("Content-Type", "image/png")
                    setBody("foo")
                }.apply {
                    response shouldHaveStatus HttpStatusCode.Created
                    // Get the rest of the conditions in here later
                }
            }
        }

        "getAllArtifacts" {
            withTest{
                httpGet("$artifactsPath/store") {
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    // Get the rest of the conditions in here later
                }
            }
        }


    }
}

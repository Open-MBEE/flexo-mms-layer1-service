package org.openmbee.flexo.mms

import io.ktor.http.*
import io.ktor.server.testing.*
import org.openmbee.flexo.mms.util.*
import org.slf4j.LoggerFactory


open class ArtifactAny : RefAny() {
    override val logger = LoggerFactory.getLogger(LockAny::class.java)

    val artifactsPath = "$demoRepoPath/artifacts"


    init {
        "post artifact text/plain" {
            withTest {
                httpPost("$artifactsPath/store") {
                    addHeader("Content-Type", "text/plain")
                    addHeader("Content-Length", "3")
                    setBody("foo")
                }.apply {
                    response shouldHaveStatus HttpStatusCode.Created
//                    response.headers["Link"] shouldMatch "artifactsPath".toRegex()
//                    response.contentType() shouldBe ContentType.Text.Plain
//                    response shouldHaveContent "foo"
                }
            }
        }
    }
}

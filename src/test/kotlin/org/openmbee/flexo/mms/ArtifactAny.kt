package org.openmbee.flexo.mms

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
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
                    response.headers["Location"] shouldContain artifactsPath
                    response.contentType() shouldBe ContentType.Text.Plain
                    //response shouldHaveContent "foo"      //Don't think this is right - shouldn't it be the id?
                }
            }
        }

        // Set a content-type with parameters (like utf-8 on text/plain) and assert that parameters have
        // been removed on returned content type
        "post artifact text/plain with parameter" {
            withTest{
                httpPost("$artifactsPath/store") {
                    addHeader("Content-Type", "text/plain; charset=utf-8")
                    setBody("foo")
                }.apply {
                    response shouldHaveStatus HttpStatusCode.Created
                    response.headers["Location"] shouldContain artifactsPath
                    response.contentType() shouldBe ContentType.Text.Plain
                }
            }
        }

        // Doesn't work since there are no artifacts and the query checks that the artifacts exist
        "get all artifacts empty" {
            withTest{
                httpGet("$artifactsPath/store") {
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    // Get the rest of the conditions in here later
                }
            }
        }

        /**
         * Add test here for getting an artifact when there's (2+) artifacts to get
         */

        "get all artifacts two artifacts" {
            withTest{
                httpPost("$artifactsPath/store") {
                    addHeader("Content-Type", "text/plain")
                    setBody("foo")
                }.apply {
                    httpPost("$artifactsPath/store") {
                        addHeader("Content-Type", "text/plain")
                        setBody("bar")
                    }.apply {
                        response shouldHaveStatus HttpStatusCode.Created
                        response.headers["Location"] shouldContain artifactsPath

                        val uri = "$artifactsPath/store/${response.headers["Location"]?.split("/")?.last()}"
                        httpGet(uri) {
                        }.apply {
                            response shouldHaveStatus HttpStatusCode.OK
                            response.contentType() shouldBe ContentType.Application.Zip
//                            response shouldHaveContent "foo"
                        }
                    }
                }
            }
        }

        // Not used http methods that should fail
        "put an artifact failing" {
            withTest{
                httpPut("$artifactsPath/store") {
                    addHeader("Content-Type", "text/plain")
                    setBody("foo")
                }.apply {
                    response shouldHaveStatus HttpStatusCode.MethodNotAllowed
                }
            }
        }

        "patch an artifact failing" {
            withTest{
                httpPatch("$artifactsPath/store") {
                    addHeader("Content-Type", "text/plain")
                    setBody("foo")
                }.apply {
                    response shouldHaveStatus HttpStatusCode.MethodNotAllowed
                }
            }
        }

        "head of an artifact failing" {
            withTest{
                httpHead("$artifactsPath/store") {
                }.apply {
                    response shouldHaveStatus HttpStatusCode.MethodNotAllowed
                }
            }
        }

        /*********************************************
        /artifacts/store/{id} route
        *********************************************/

        "get an artifact by id - text" {
            withTest{
                httpPost("$artifactsPath/store") {
                    addHeader("Content-Type", "text/plain")
                    setBody("foo")
                }.apply {
                    response shouldHaveStatus HttpStatusCode.Created
                    response.headers["Location"] shouldContain artifactsPath

                    val uri = "$artifactsPath/store/${response.headers["Location"]?.split("/")?.last()}"
                    httpGet(uri) {
                    }.apply {
                        response shouldHaveStatus HttpStatusCode.OK
                        response.contentType() shouldBe ContentType.Text.Plain
                        response shouldHaveContent "foo"
                    }
                }
            }
        }

        // Should be binary according to line 49, ArtifactWrite.kt - content-type is not text/...
        // TODO: this isn't necessarily needed - I want to see if it's an xstring - ArtifactStoreRead.kt, line 77
        "get an artifact by id - BINARY?" {
            withTest{
                httpPost("$artifactsPath/store") {
                    addHeader("Content-Type", "application/octet-stream")
                    setBody("foo".toByteArray())
                }.apply {
                    response shouldHaveStatus HttpStatusCode.Created
                    response.headers["Location"] shouldContain artifactsPath

                    val uri = "$artifactsPath/store/${response.headers["Location"]?.split("/")?.last()}"
                    httpGet(uri) {
                    }.apply {
                        response shouldHaveStatus HttpStatusCode.OK
                        response.contentType() shouldBe ContentType.Text.Plain
                        //response shouldHaveContent "foo"
                    }
                }
            }
        }

        // TODO: the URI test - needs work for that in the backend, not an option in ArtifactWrite

        // Copy the above one for head, and test get for URI and Binary types of bodies - return when
        // statement in ArtifactStoreRead.kt

        "put getting an artifact failing" {
            withTest{
                httpPost("$artifactsPath/store") {
                    addHeader("Content-Type", "text/plain")
                    setBody("foo")
                }.apply {
                    response shouldHaveStatus HttpStatusCode.Created
                    response.headers["Location"] shouldContain artifactsPath

                    val uri = "$artifactsPath/store/${response.headers["Location"]?.split("/")?.last()}"
                    httpPut(uri) {
                    }.apply {
                        response shouldHaveStatus HttpStatusCode.MethodNotAllowed
                    }
                }
            }
        }

        "patch getting an artifact failing" {
            withTest{
                httpPost("$artifactsPath/store") {
                    addHeader("Content-Type", "text/plain")
                    setBody("foo")
                }.apply {
                    response shouldHaveStatus HttpStatusCode.Created
                    response.headers["Location"] shouldContain artifactsPath

                    val uri = "$artifactsPath/store/${response.headers["Location"]?.split("/")?.last()}"
                    httpPatch(uri) {
                    }.apply {
                        response shouldHaveStatus HttpStatusCode.MethodNotAllowed
                    }
                }
            }
        }

        "post an artifact failing" {
            withTest{
                httpPost("$artifactsPath/store") {
                    addHeader("Content-Type", "text/plain")
                    setBody("foo")
                }.apply {
                    response shouldHaveStatus HttpStatusCode.Created
                    response.headers["Location"] shouldContain artifactsPath

                    val uri = "$artifactsPath/store/${response.headers["Location"]?.split("/")?.last()}"
                    httpPost(uri) {
                    }.apply {
                        response shouldHaveStatus HttpStatusCode.MethodNotAllowed
                    }
                }
            }
        }
    }
}


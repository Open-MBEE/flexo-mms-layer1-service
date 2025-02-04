package org.openmbee.flexo.mms

import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.http.*
import io.ktor.server.testing.*
import org.openmbee.flexo.mms.util.*
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

open class ArtifactAny : RefAny() {
    override val logger = LoggerFactory.getLogger(LockAny::class.java)

    val artifactsPath = "$demoRepoPath/artifacts"


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

        "get all artifacts empty" {
            withTest{
                httpGet("$artifactsPath/store") {
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    // Get the rest of the conditions in here later
                }
            }
        }


        "get all artifacts two artifacts" {
            withTest{
                httpPost("$artifactsPath/store") {
                    addHeader("Content-Type", "text/plain")
                    setBody("foo")
                }.apply {
                    val locationFile1 =  response.headers["Location"]?.split("/")?.last()
                    httpPost("$artifactsPath/store") {
                        addHeader("Content-Type", "text/plain")
                        setBody("bar")
                    }.apply {
                        val locationFile2 =  response.headers["Location"]?.split("/")?.last()
                        httpGet("$artifactsPath/store") {
                        }.apply {
                            response shouldHaveStatus HttpStatusCode.OK
                            response.contentType() shouldBe ContentType.Application.Zip

                            val zipBytes = response.byteContent ?: throw IllegalStateException("Response byteContent is null")
                            val contents = readZipContents(zipBytes)
                            contents.size shouldBe 2
                            contents["$locationFile1.cc"] shouldBe "foo"
                            contents["$locationFile2.cc"] shouldBe "bar"

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

        "get an artifact by id - binary" {
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
                        response.contentType() shouldBe ContentType.Application.OctetStream
                        response shouldHaveContent "foo"
                    }
                }
            }
        }

        "get an artifact by id - URI" {
            withTest{
                httpPost("$artifactsPath/store") {
                    addHeader("Content-Type", "text/turtle")
                    setBody("<http://openmbee.org> <http://openmbee.org> <http://openmbee.org> .")
                }.apply {
                    response shouldHaveStatus HttpStatusCode.Created
                    response.headers["Location"] shouldContain artifactsPath

                    val uri = "$artifactsPath/store/${response.headers["Location"]?.split("/")?.last()}"
                    httpGet(uri) {
                    }.apply {
                        response shouldHaveStatus HttpStatusCode.OK
                        response.contentType().toString() shouldBeEqual "text/turtle"
                        response shouldHaveContent "<http://openmbee.org> <http://openmbee.org> <http://openmbee.org> ."
                    }
                }
            }
        }

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

/**
 * Reads all files in a zip file and returns map of filename to file contents
 */
fun readZipContents(zipBytes: ByteArray): Map<String, String> {
    val contents = mutableMapOf<String, String>()
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zipInputStream ->
        var entry: ZipEntry? = zipInputStream.nextEntry
        while (entry != null) {
            if (!entry.isDirectory) {
                val fileName = entry.name
                // Read the content of the current entry as a String
                val fileContent = zipInputStream.bufferedReader(Charsets.UTF_8).readText()
                contents[fileName] = fileContent
            }
            zipInputStream.closeEntry() // Close the current entry
            entry = zipInputStream.nextEntry // Move to the next entry
        }
    }
    return contents
}

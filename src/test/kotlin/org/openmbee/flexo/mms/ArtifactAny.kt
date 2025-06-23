package org.openmbee.flexo.mms

import io.kotest.assertions.ktor.client.shouldHaveStatus
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.util.*
import org.openmbee.flexo.mms.util.*
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream


@OptIn(InternalAPI::class)
open class ArtifactAny : RefAny() {
    override val logger = LoggerFactory.getLogger(LockAny::class.java)

    val artifactsPath = "$demoRepoPath/artifacts"


    init {
        "post artifact text/plain" {
            testApplication {
                httpPost(artifactsPath) {
                    header("Content-Type", "text/plain")
                    setBody("foo")
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Created
                    this.headers[HttpHeaders.Location] shouldStartWith localIri(artifactsPath)
                    this.contentType() shouldBe ContentType.Text.Plain
                }
            }
        }

        // Set a content-type with parameters (like utf-8 on text/plain) and assert that parameters have
        // been removed on returned content type
        "post artifact text/plain with parameter" {
            testApplication {
                httpPost(artifactsPath) {
                    header("Content-Type", "text/plain; charset=utf-8")
                    setBody("foo")
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Created
                    this.headers[HttpHeaders.Location] shouldStartWith localIri(artifactsPath)
                    this.contentType() shouldBe ContentType.Text.Plain
                }
            }
        }

        "get all artifacts empty" {
            testApplication {
                httpGet("$artifactsPath?download") {
                }.apply {
                    this shouldHaveStatus HttpStatusCode.NoContent
                    this.bodyAsText().shouldBeEmpty()
                }
            }
        }


        "get all artifacts two artifacts" {
            testApplication {
                httpPost(artifactsPath) {
                    header("Content-Type", "text/plain")
                    setBody("foo")
                }.apply {
                    val locationFile1 = getURI(this.headers[HttpHeaders.Location].toString())
                    httpPost(artifactsPath) {
                        header("Content-Type", "application/octet-stream")
                        setBody("bar".toByteArray())
                    }.apply {
                        val locationFile2 = getURI(this.headers[HttpHeaders.Location].toString())
                        httpGet("$artifactsPath?download") {}.apply {
                            this shouldHaveStatus HttpStatusCode.OK
                            this.contentType() shouldBe ContentType.Application.Zip

                            val zipBytes = this.content.toByteArray()
                            val contents = readZipContents(zipBytes)
                            contents.size shouldBe 2
                            contents["$locationFile1.txt"] shouldBe "foo"
                            contents["$locationFile2.bin"] shouldBe "bar"
                        }
                    }
                }
            }
        }

        // Not used http methods that should fail
        "artifact rejects other methods" {
            testApplication {
                onlyAllowsMethods(artifactsPath, setOf(
                    HttpMethod.Head,
                    HttpMethod.Get,
                    HttpMethod.Post
                ))
            }
        }

        /*********************************************
        /artifacts/{id} route - gets artifacts
        *********************************************/

        "get an artifact by id - turtle" {
            testApplication {
                httpPost(artifactsPath) {
                    header("Content-Type", "text/plain")
                    setBody("foo".toByteArray())
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Created
                    this.headers[HttpHeaders.Location] shouldStartWith localIri(artifactsPath)

                    val uri = getLocation(this.headers[HttpHeaders.Location].toString())
                    httpGet(uri) {}.apply {
                        this shouldHaveStatus HttpStatusCode.OK
                        this.contentType() shouldBe ContentType.Text.Plain
                        this.bodyAsText() shouldContain "foo"
                    }
                }
            }
        }

        "download an artifact by id - text" {
            testApplication {
                httpPost(artifactsPath) {
                    header("Content-Type", "text/plain")
                    setBody("foo")
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Created
                    this.headers[HttpHeaders.Location] shouldStartWith localIri(artifactsPath)

                    val uri = getLocation(this.headers[HttpHeaders.Location].toString())
                    httpGet("$uri?download") {
                    }.apply {
                        this shouldHaveStatus HttpStatusCode.OK
                        this.contentType() shouldBe ContentType.Text.Plain
                        this.bodyAsText() shouldContain "foo"
                    }
                }
            }
        }

        "download an artifact by id - binary" {
            testApplication {
                httpPost(artifactsPath) {
                    header("Content-Type", "application/octet-stream")
                    setBody("foo".toByteArray())
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Created
                    this.headers[HttpHeaders.Location] shouldStartWith localIri(artifactsPath)

                    val uri = getLocation(this.headers[HttpHeaders.Location].toString())
                    httpGet("$uri?download") {
                    }.apply {
                        this shouldHaveStatus HttpStatusCode.OK
                        this.contentType() shouldBe ContentType.Application.OctetStream
                        this.bodyAsText() shouldContain "foo"
                    }
                }
            }
        }

        "get an artifact by id - URI" {
            testApplication {
                httpPost(artifactsPath) {
                    header("Content-Type", "text/turtle")
                    setBody("<http://openmbee.org> <http://openmbee.org> <http://openmbee.org> .")
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Created
                    this.headers[HttpHeaders.Location] shouldStartWith localIri(artifactsPath)

                    val uri = getLocation(this.headers[HttpHeaders.Location].toString())
                    httpGet("$uri?download") {}.apply {
                        this shouldHaveStatus HttpStatusCode.OK
                        this.contentType().toString() shouldBeEqual "text/turtle"
                        this.bodyAsText() shouldBeEqual "<http://openmbee.org> <http://openmbee.org> <http://openmbee.org> ."
                    }
                }
            }
        }

        // Not used http methods that should fail
        "artifact/{id} rejects other methods" {
            testApplication {
                httpPost(artifactsPath) {
                    header("Content-Type", "text/plain")
                    setBody("foo")
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Created
                    this.headers[HttpHeaders.Location] shouldStartWith localIri(artifactsPath)

                    val uri = getLocation(this.headers[HttpHeaders.Location].toString())
                    onlyAllowsMethods(
                        uri, setOf(
                            HttpMethod.Head,
                            HttpMethod.Get
                        )
                    )
                }
            }
        }
    }

    // Just gets the artifactsPath/{id} part of the location
    fun getLocation(path: String): String{
        return path.removePrefix(ROOT_CONTEXT)
    }

    fun getURI(path: String): String{
        return path.removePrefix("${ROOT_CONTEXT}$artifactsPath/")
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

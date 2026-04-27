package org.openmbee.flexo.mms

import io.kotest.assertions.ktor.client.shouldHaveStatus
import io.ktor.client.request.*
import io.ktor.http.*
import org.openmbee.flexo.mms.util.*
import org.slf4j.LoggerFactory


class CollectionValidationTest : CollectionAny() {
    override val logger = LoggerFactory.getLogger(CollectionValidationTest::class.java)

    init {
        "PUT collection - reject non-existent ref with 404" {
            testApplication {
                val nonExistentRef = "$demoRepoPath/branches/does-not-exist"
                val body = withAllTestPrefixes("""
                    <> dct:title "$demoCollectionName"@en .
                    <> mms:collects <$nonExistentRef> .
                """.trimIndent())

                httpPut(demoCollectionPath) {
                    setTurtleBody(body)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }

        "PUT collection - reject mix of existing and non-existent refs with 404" {
            testApplication {
                val nonExistentRef = "$demoRepoPath/branches/does-not-exist"
                val body = withAllTestPrefixes("""
                    <> dct:title "$demoCollectionName"@en .
                    <> mms:collects <$demoBranchRef> .
                    <> mms:collects <$nonExistentRef> .
                """.trimIndent())

                httpPut(demoCollectionPath) {
                    setTurtleBody(body)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }

        "POST collections - reject non-existent ref with 404" {
            testApplication {
                val nonExistentRef = "$demoRepoPath/locks/no-such-lock"
                val body = withAllTestPrefixes("""
                    <> dct:title "$demoCollectionName"@en .
                    <> mms:collects <$nonExistentRef> .
                """.trimIndent())

                httpPost(basePathCollections) {
                    header(HttpHeaders.SLUG, demoCollectionId)
                    setTurtleBody(body)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }

        "PUT collection - reject ref without admin permission with 403" {
            testApplication {
                // create collection as non-root user who has no admin policy on the ref
                val noPermUser = AuthStruct("noperm")
                val body = withAllTestPrefixes("""
                    <> dct:title "$demoCollectionName"@en .
                    <> mms:collects <$demoBranchRef> .
                """.trimIndent())

                client.request {
                    this.method = HttpMethod.Put
                    this.url(demoCollectionPath)
                    header("Authorization", authorization(noPermUser))
                    setTurtleBody(body)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Forbidden
                }
            }
        }

        "PUT collection - existing tests still pass with admin user" {
            testApplication {
                httpPut(demoCollectionPath) {
                    setTurtleBody(withAllTestPrefixes(validCollectionBody))
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Created
                }
            }
        }
    }
}

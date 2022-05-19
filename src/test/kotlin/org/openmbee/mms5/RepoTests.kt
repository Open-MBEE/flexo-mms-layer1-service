package org.openmbee.mms5

import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Test
import org.openmbee.mms5.util.TestBase
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepoTests : TestBase() {

    @Test
    fun createOnNonExistentOrg() {
        withTestEnvironment {
            val put = handleRequest(HttpMethod.Put, "/orgs/testCreateOnNonExistentOrg/repos/new-repo") {
                addAuthorizationHeader("root", listOf("SuperAdmins"))
                setTurtleBody("""
                    <>
                        dct:title "TMT"@en ;
                        mms:org m-org:testCreateOnNonExistentOrg ;
                        <https://demo.org/custom/prop> "2" ;
                        .
                """.trimIndent())
            }
            assertFalse(put.response.status()?.isSuccess() ?: true, "Failed to create project on non-existent org")
        }
    }

    @Test
    fun createOnValidOrg() {
        withTestEnvironment {
            val put = handleRequest(HttpMethod.Put, "/orgs/testCreateOnNonExistentOrg/repos/new-repo") {
                addAuthorizationHeader("root", listOf("SuperAdmins"))
                setTurtleBody("""
                    <>
                        dct:title "TMT"@en ;
                        mms:org m-org:testCreateOnNonExistentOrg ;
                        <https://demo.org/custom/prop> "2" ;
                        .
                """.trimIndent())
            }
            assertTrue(put.response.status()?.isSuccess() ?: false, "Failed to create project on non-existent org")
        }
    }

}
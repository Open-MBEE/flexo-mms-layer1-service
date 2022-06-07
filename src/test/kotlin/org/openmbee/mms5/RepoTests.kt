package org.openmbee.mms5

import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Test
import org.openmbee.mms5.util.AuthObject
import org.openmbee.mms5.util.TestBase
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepoTests : TestBase() {

    private val defaultAuthObject = AuthObject(
        username = "root",
        groups = listOf("super_admins")
    )
    private val testOrgId = "testCreateAndReadOrg"
    private val testOrgName = "OpenMBEE"

    @Test
    fun createOnNonExistentOrg() {
        withTestEnvironment {
            val put = handleRequest(HttpMethod.Put, "/orgs/$testOrgId/repos/new-repo") {
                addAuthorizationHeader(defaultAuthObject)
                setTurtleBody("""
                    <>
                        dct:title "TMT"@en ;
                        mms:org m-org:$testOrgId ;
                        <https://demo.org/custom/prop> "2" ;
                        .
                """.trimIndent())
            }
            assertFalse(put.response.status()?.isSuccess() ?: false, "Create project on non-existent org")
        }
    }

    @Test
    fun createOnValidOrg() {
        doCreateOrg(defaultAuthObject, testOrgId, testOrgName)
        withTestEnvironment {
            val put = handleRequest(HttpMethod.Put, "/orgs/$testOrgId/repos/new-repo") {
                addAuthorizationHeader(defaultAuthObject)
                setTurtleBody("""
                    <>
                        dct:title "TMT"@en ;
                        mms:org m-org:$testOrgId ;
                        <https://demo.org/custom/prop> "2" ;
                        .
                """.trimIndent())
            }
            println("createOnValidOrg headers: " + put.response.headers.allValues().toString())
            println("createOnValidOrg status: " + put.response.status())
            println("createOnValidOrg content: " + put.response.content)
            assertTrue(put.response.status()?.isSuccess() ?: true, "Create project on valid org")
        }
    }

}
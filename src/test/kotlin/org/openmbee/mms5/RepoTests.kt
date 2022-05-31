package org.openmbee.mms5

import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Test
import org.openmbee.mms5.util.TestBase
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepoTests : TestBase() {

    private val username = "root"
    private val groups = listOf("SuperAdmins")
    private val testOrgId = "testCreateAndReadOrg"
    private val testOrgName = "OpenMBEE"

    fun doCreateOrg(orgId: String, orgName: String): TestApplicationCall {
        return withTestEnvironment {
            handleRequest(HttpMethod.Put, "/orgs/$orgId") {
                addAuthorizationHeader(username, groups)
                setTurtleBody("""
                    <> dct:title "$orgName"@en ;
                """.trimIndent())
            }
        }
    }

    @Test
    fun createOnNonExistentOrg() {
        withTestEnvironment {
            val put = handleRequest(HttpMethod.Put, "/orgs/$testOrgId/repos/new-repo") {
                addAuthorizationHeader(username, groups)
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
        doCreateOrg(testOrgId, testOrgName)
        withTestEnvironment {
            val put = handleRequest(HttpMethod.Put, "/orgs/$testOrgId/repos/new-repo") {
                addAuthorizationHeader(username, groups)
                setTurtleBody("""
                    <>
                        dct:title "TMT"@en ;
                        mms:org m-org:$testOrgId ;
                        <https://demo.org/custom/prop> "2" ;
                        .
                """.trimIndent())
            }
            assertTrue(put.response.status()?.isSuccess() ?: true, "Create project on valid org")
        }
    }

}
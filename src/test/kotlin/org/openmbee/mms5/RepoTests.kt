package org.openmbee.mms5

import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.openmbee.mms5.util.AuthObject
import org.openmbee.mms5.util.TestBase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepoTests : TestBase() {

    private val defaultAuthObject = AuthObject(
        username = "root",
        groups = listOf("super_admins")
    )
    private val testOrgId = "repoTests"
    private val testOrgName = "Repo Tests"


    @Test
    @Order(1)
    fun createOnNewOrg() {
        doCreateOrg(defaultAuthObject, testOrgId, testOrgName)

        withTestEnvironment {
            val put = handleRequest(HttpMethod.Put, "/orgs/$testOrgId/repos/new-repo") {
                addAuthorizationHeader(defaultAuthObject)
                setTurtleBody("""
                    <>
                        dct:title "$testOrgName"@en ;
                        <https://demo.org/custom/prop> "test" ;
                        .
                """.trimIndent())
            }
            println("createOnValidOrg headers: " + put.response.headers.allValues().toString())
            println("createOnValidOrg status: " + put.response.status())
            println("createOnValidOrg content: " + put.response.content)
            assertTrue(put.response.status()?.isSuccess() ?: true, "Create repo on valid org")
        }
    }

    @Test
    @Order(2)
    fun invalidRepoId() {
        withTestEnvironment {
            val put = handleRequest(HttpMethod.Put, "/orgs/$testOrgId/repos/invalid%20id") {
                addAuthorizationHeader(defaultAuthObject)
                setTurtleBody("""<> dct:title "$testOrgName"@en .""")
            }

            assertEquals("400 HTTP Status", 400, put.response.status())
                put.response.status()?.isSuccess() ?: false, "Create repo with invalid id")
        }
    }
}
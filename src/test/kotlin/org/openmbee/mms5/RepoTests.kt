package org.openmbee.mms5

import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Order
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
    @Order(2)
    fun createOnValidOrg() {
        doCreateOrg(defaultAuthObject, testOrgId, testOrgName)

        Thread.sleep(20000)

        withTestEnvironment {
            val put = handleRequest(HttpMethod.Put, "/orgs/$testOrgId/repos/new-repo") {
                addAuthorizationHeader(defaultAuthObject)
                setTurtleBody("""
                    <>
                        dct:title "TMT"@en ;
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
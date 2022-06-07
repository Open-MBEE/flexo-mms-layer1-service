package org.openmbee.mms5

import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.*
import org.openmbee.mms5.util.AuthObject
import org.openmbee.mms5.util.TestBase
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OrgTests : TestBase() {

    private val defaultAuthObject = AuthObject(
        username = "root",
        groups = listOf("super_admins")
    )
    private val testOrgId = "testCreateAndReadOrg"
    private val testOrgName = "OpenMBEE"

    @Test
    @Order(1)
    fun createAndReadOrgWithIfMatchSuccess() {
        val putOrg = doCreateOrg(defaultAuthObject, testOrgId, testOrgName)

        assertEquals(HttpStatusCode.OK, putOrg.response.status(), "PUT Org Successful")
        val etag = putOrg.response.headers["ETag"]
        assertNotNull(etag, "Etag Present")

        // Read with Etag OK
        withTestEnvironment {
            val getOrg = doGetOrg(defaultAuthObject, testOrgId, mapOf("If-Match" to '"' + etag + '"'))
            assertEquals(HttpStatusCode.OK, getOrg.response.status(), "GET Org Successful")
            val match = findOneInResponse(getOrg, """
                SELECT ?title WHERE {
                    ?org a mms:Org ;
                         dct:title ?title
                }
            """.trimIndent())
            assertEquals(testOrgName, match.getLiteral("title").string)
        }
    }

    @Test
    @Order(2)
    fun createAndReadOrgWithNoETag() {
        val etag = doGetOrg(defaultAuthObject, testOrgId).response.headers["Etag"]
        withTestEnvironment {
            val getOrg = doGetOrg(defaultAuthObject, testOrgId)
            assertEquals(HttpStatusCode.OK, getOrg.response.status(), "GET Org Successful")
            assertEquals(etag, getOrg.response.headers["ETag"], "Etag unchanged")

            val match = findOneInResponse(getOrg, """
                SELECT ?title WHERE {
                    ?org a mms:Org ;
                         dct:title ?title
                }
            """.trimIndent())
            assertEquals(testOrgName, match.getLiteral("title").string)
        }
    }

    @Test
    @Order(3)
    fun createAndReadWithIfMatchFailed() {
        doCreateOrg(defaultAuthObject, testOrgId, testOrgName)

        // If-Match different etag
        withTestEnvironment {
            val getOrg = doGetOrg(defaultAuthObject, testOrgId, mapOf("If-Match" to '"' + UUID.randomUUID().toString() + '"'))
            assertEquals(HttpStatusCode.PreconditionFailed, getOrg.response.status(), "Precondition Failed")
        }
    }

    @Test
    @Order(4)
    fun createAndReadWithIfNoneMatchSameEtag() {
        val createOrg = doCreateOrg(defaultAuthObject, testOrgId, testOrgName)
        val etag = createOrg.response.headers["ETag"]
        // If-None-Match same etag
        withTestEnvironment {
            val getOrg = doGetOrg(defaultAuthObject, testOrgId, mapOf("If-None-Match" to "\"$etag\""))
            // Expect 304 Not Modified
            assertTrue(getOrg.response.status()?.isSuccess() ?: false, "Success with If-None-Match same etag ${getOrg.response.status()}")
        }
    }

    //@Test
    @Order(5)
    fun createAndDeleteOrg() {
        doCreateOrg(defaultAuthObject, testOrgId, testOrgName)

        // Delete
        withTestEnvironment {
            val delete = handleRequest(HttpMethod.Delete, "/orgs/$testOrgId") {
                addAuthorizationHeader(defaultAuthObject)
            }

            assertTrue(delete.response.status()?.isSuccess() ?: false, "DELETE worked")
        }

        // Get deleted org should fail
        withTestEnvironment {
            val getOrg = doGetOrg(defaultAuthObject, testOrgId)
            Assertions.assertFalse(getOrg.response.status()?.isSuccess() ?: false, "Getting deleted org fails")
        }
    }

    @Test
    @Order(6)
    fun testReadNonExistentOrg() {
        withTestEnvironment {
            val getOrg = doGetOrg(defaultAuthObject, "testReadNonExistentOrg")
            println("listAllOrgs headers: " + getOrg.response.headers.allValues().toString())
            println("listAllOrgs status: " + getOrg.response.status())
            println("listAllOrgs content: " + getOrg.response.content)
            assertEquals(HttpStatusCode.NotFound, getOrg.response.status(), "Non existent org not found")
        }
    }

    @Test
    @Order(7)
    fun testUpdateOrg() {
        val orgNameUpdated = "OpenMBEE testUpdateOrg"

        doCreateOrg(defaultAuthObject, testOrgId, orgNameUpdated)

        withTestEnvironment {
            handleRequest(HttpMethod.Patch, "/orgs/$testOrgId") {
                addAuthorizationHeader(defaultAuthObject)
                setSparqlUpdateBody("""
                    prefix foaf: <http://xmlns.com/foaf/0.1/>
                    prefix custom: <https://custom.example/ontology/>
                    prefix mms: <https://mms.openmbee.org/rdf/ontology/>
                    
                    delete {
                        <> foaf:homepage ?homepage ;
                            foaf:mbox ?mbox ;
                            .
                    }
                    insert {
                        <> foaf:homepage <https://www.openmbee.org/> ;
                            foaf:mbox <mailto:openmbee@gmail.com> ;
                            .
                    }
                    where {
                        optional {
                            <> foaf:homepage ?homepage ;
                            foaf:mbox ?mbox ;
                            .
                        }
                    }
                """.trimIndent())
            }
            val get = doGetOrg(defaultAuthObject, testOrgId)
            assertTrue(get.response.status()?.isSuccess() ?: false, "")
        }
    }

    @Test
    @Order(8)
    fun listAllOrgs() {
        doCreateOrg(defaultAuthObject, "org1", "Org 1")
        doCreateOrg(defaultAuthObject, "org2", "Org 2")
        withTestEnvironment {
            val getOrg = doGetOrg(defaultAuthObject)
            println("listAllOrgs headers: " + getOrg.response.headers.allValues().toString())
            println("listAllOrgs status: " + getOrg.response.status())
            println("listAllOrgs content: " + getOrg.response.content)
            assertTrue(getOrg.response.status()?.isSuccess() ?: false, "Get /orgs success")
        }
    }
}
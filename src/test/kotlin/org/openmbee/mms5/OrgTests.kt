package org.openmbee.mms5

import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.*
import org.openmbee.mms5.util.TestBase
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OrgTests : TestBase() {

    private val username = "root"
    private val groups = listOf("super_admins")
    private val testOrgId = "testCreateAndReadOrg"
    private val testOrgName = "OpenMBEE"

    private fun doCreateOrg(orgId: String, orgName: String): TestApplicationCall {
        return withTestEnvironment {
            handleRequest(HttpMethod.Put, "/orgs/$orgId") {
                addAuthorizationHeader(username, groups)
                setTurtleBody("""
                    <> dct:title "$orgName"@en ;
                """.trimIndent())
            }
        }
    }

    private fun doGetOrg(orgId: String? = "", headers: Map<String, String>? = null): TestApplicationCall {
        return withTestEnvironment {
            handleRequest(HttpMethod.Get, "/orgs/$orgId") {
                addAuthorizationHeader(username, groups)
                headers?.forEach { header ->
                    addHeader(header.key, header.value)
                }
            }
        }
    }

    @Test
    @Order(1)
    fun createAndReadOrgWithIfMatchSuccess() {
        val putOrg = doCreateOrg(testOrgId, testOrgName)

        assertEquals(HttpStatusCode.OK, putOrg.response.status(), "PUT Org Successful")
        val etag = putOrg.response.headers["ETag"]
        assertNotNull(etag, "Etag Present")

        // Read with Etag OK
        withTestEnvironment {
            val getOrg = doGetOrg(testOrgId, mapOf("If-Match" to '"' + etag + '"'))
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
        val etag = doGetOrg(testOrgId).response.headers["Etag"]
        withTestEnvironment {
            val getOrg = doGetOrg(testOrgId)
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
        doCreateOrg(testOrgId, testOrgName)

        // If-Match different etag
        withTestEnvironment {
            val getOrg = doGetOrg(testOrgId, mapOf("If-Match" to '"' + UUID.randomUUID().toString() + '"'))
            assertEquals(HttpStatusCode.PreconditionFailed, getOrg.response.status(), "Precondition Failed")
        }
    }

    @Test
    @Order(4)
    fun createAndReadWithIfNoneMatchSameEtag() {
        val createOrg = doCreateOrg(testOrgId, testOrgName)
        val etag = createOrg.response.headers["ETag"]
        // If-None-Match same etag
        withTestEnvironment {
            val getOrg = doGetOrg(testOrgId, mapOf("If-None-Match" to "\"$etag\""))
            // Expect 304 Not Modified
            assertTrue(getOrg.response.status()?.isSuccess() ?: false, "Success with If-None-Match same etag ${getOrg.response.status()}")
        }
    }

    //@Test
    @Order(5)
    fun createAndDeleteOrg() {
        doCreateOrg(testOrgId, testOrgName)

        // Delete
        withTestEnvironment {
            val delete = handleRequest(HttpMethod.Delete, "/orgs/$testOrgId") {
                addAuthorizationHeader(username, groups)
            }

            assertTrue(delete.response.status()?.isSuccess() ?: false, "DELETE worked")
        }

        // Get deleted org should fail
        withTestEnvironment {
            val getOrg = doGetOrg(testOrgId)
            Assertions.assertFalse(getOrg.response.status()?.isSuccess() ?: false, "Getting deleted org fails")
        }
    }

    @Test
    @Order(6)
    fun testReadNonExistentOrg() {
        withTestEnvironment {
            val getOrg = doGetOrg("testReadNonExistentOrg")
            assertEquals(HttpStatusCode.NotFound, getOrg.response.status(), "Non existent org not found")
        }
    }

    @Test
    @Order(7)
    fun testUpdateOrg() {
        val orgNameUpdated = "OpenMBEE testUpdateOrg"

        doCreateOrg(testOrgId, orgNameUpdated)

        withTestEnvironment {
            handleRequest(HttpMethod.Patch, "/orgs/$testOrgId") {
                addAuthorizationHeader(username, groups)
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
            val get = doGetOrg(testOrgId)
            assertTrue(get.response.status()?.isSuccess() ?: false, "")
        }
    }

    @Test
    @Order(8)
    fun listAllOrgs() {
        doCreateOrg("org1", "Org 1")
        doCreateOrg("org2", "Org 2")
        withTestEnvironment {
            val get = doGetOrg()
            println("listAllOrgs headers: " + get.response.headers.allValues().toString())
            println("listAllOrgs status: " + get.response.status())
            println("listAllOrgs content: " + get.response.content)
            assertTrue(get.response.status()?.isSuccess() ?: false, "Get /orgs success")
        }
    }
}
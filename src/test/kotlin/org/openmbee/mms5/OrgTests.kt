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
    fun createAndReadOrgWithIfMatchSuccess() {
        val putOrg = doCreateOrg(testOrgId, testOrgName)
        println("CreateOrg Response Status" + putOrg.response.status())
        assertEquals(HttpStatusCode.OK, putOrg.response.status(), "PUT Org Successful")
        val etag = putOrg.response.headers["ETag"]
        assertNotNull(etag, "Etag Present")

        // Read with Etag OK
        withTestEnvironment {
            val getOrg = handleRequest(HttpMethod.Get, "/orgs/$testOrgId") {
                addAuthorizationHeader(username, groups)
                addHeader("If-Match", '"' + etag + '"')
            }
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
    fun createAndReadOrgWithNoETag() {
        val etag = doCreateOrg(testOrgId, testOrgName).response.headers["Etag"]
        withTestEnvironment {
            val getOrg = handleRequest(HttpMethod.Get, "/orgs/$testOrgId") {
                addAuthorizationHeader(username, groups)
            }
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
    fun createAndReadWithIfMatchFailed() {
        doCreateOrg(testOrgId, testOrgName)

        // If-Match different etag
        withTestEnvironment {
            val getOrg = handleRequest(HttpMethod.Get, "/orgs/$testOrgId") {
                addAuthorizationHeader(username, groups)
                addHeader("If-Match", '"' + UUID.randomUUID().toString() + '"')
            }
            assertEquals(HttpStatusCode.PreconditionFailed, getOrg.response.status(), "Precondition Failed")
        }
    }

    @Test
    fun createAndReadWithIfNoneMatchSameEtag() {
        val etag = doCreateOrg(testOrgId, testOrgName).response.headers["ETag"]
        // If-None-Match same etag
        withTestEnvironment {
            val getOrg = handleRequest(HttpMethod.Get, "/orgs/$testOrgId") {
                addAuthorizationHeader(username, groups)
                addHeader("If-None-Match", "\"$etag\"")
            }
            // Expect 304 Not Modified
            assertTrue(getOrg.response.status()?.isSuccess() ?: false, "Success with If-None-Match same etag ${getOrg.response.status()}")
        }
    }

    @Test
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
            val getOrg = handleRequest(HttpMethod.Get, "/orgs/$testOrgId") {
                addAuthorizationHeader(username, groups)
            }
            Assertions.assertFalse(getOrg.response.status()?.isSuccess() ?: false, "Getting deleted org fails")
        }
    }

    //@Test
    fun testReadNonExistentOrg() {
        withTestEnvironment {
            val getOrg = handleRequest(HttpMethod.Get, "/orgs/testReadNonExistentOrg") {
                addAuthorizationHeader(username, groups)
            }
            assertEquals(HttpStatusCode.NotFound, getOrg.response.status(), "Non existent org not found")
        }
    }

    //@Test
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
            val get = handleRequest(HttpMethod.Get, "/orgs/$testOrgId") {
                addAuthorizationHeader(username, groups)
            }
            assertTrue(get.response.status()?.isSuccess() ?: false, "")
        }
    }

    //@Test
    fun listAllOrgs() {
        doCreateOrg("org1", "Org 1")
        doCreateOrg("org2", "Org 2")
        withTestEnvironment {
            val get = handleRequest(HttpMethod.Get, "/orgs") {
                addAuthorizationHeader(username, groups)
            }
            assertTrue(get.response.status()?.isSuccess() ?: false, "Get /orgs success")
        }
    }
}
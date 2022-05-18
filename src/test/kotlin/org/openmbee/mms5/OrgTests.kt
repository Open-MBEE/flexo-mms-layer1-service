package org.openmbee.mms5

import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.*
import org.openmbee.mms5.MMS.etag
import org.openmbee.mms5.util.TestBase
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OrgTests : TestBase() {

    fun doCreateOrg(orgId: String, orgName: String): TestApplicationCall {
        println("Running tests")
        return withTestEnvironment {
            handleRequest(HttpMethod.Put, "/orgs/$orgId") {
                addAuthorizationHeader("root")
                setTurtleBody("""
                    <> dct:title "$orgName"@en ;
                """.trimIndent())
            }
        }
    }

    @Test
    fun createAndReadOrgWithIfMatchSuccess() {
        val orgId = "testCreateAndReadOrg";
        val orgName = "OpenMBEE";

        val putOrg = doCreateOrg(orgId, orgName)
        println(putOrg.response.toString())
        assertEquals(HttpStatusCode.OK, putOrg.response.status(), "PUT Org Successful")
        val etag = putOrg.response.headers["ETag"]
        assertNotNull(etag, "Etag Present")

        // Read with Etag OK
        withTestEnvironment {
            val getOrg = handleRequest(HttpMethod.Get, "/orgs/$orgId") {
                addAuthorizationHeader("root")
                addHeader("If-Match", '"' + etag + '"')
            }
            assertEquals(HttpStatusCode.OK, getOrg.response.status(), "GET Org Successful")
            val match = findOneInResponse(getOrg, """
                SELECT ?title WHERE {
                    ?org a mms:Org ;
                         dct:title ?title
                }
            """.trimIndent())
            assertEquals(orgName, match.getLiteral("title").string)
        }
    }

    @Test
    fun createAndReadOrgWithNoETag() {
        val orgId = "testCreateAndReadOrg";
        val orgName = "OpenMBEE";

        val etag = doCreateOrg(orgId, orgName).response.headers["Etag"]
        withTestEnvironment {
            val getOrg = handleRequest(HttpMethod.Get, "/orgs/$orgId") {
                addAuthorizationHeader("root")
            }
            assertEquals(HttpStatusCode.OK, getOrg.response.status(), "GET Org Successful")
            assertEquals(etag, getOrg.response.headers["ETag"], "Etag unchanged")

            val match = findOneInResponse(getOrg, """
                SELECT ?title WHERE {
                    ?org a mms:Org ;
                         dct:title ?title
                }
            """.trimIndent())
            assertEquals(orgName, match.getLiteral("title").string)
        }
    }

    @Test
    fun createAndReadWithIfMatchFailed() {
        val orgId = "testCreateAndReadOrg";
        val orgName = "OpenMBEE";

        doCreateOrg(orgId, orgName)

        // If-Match different etag
        withTestEnvironment {
            val getOrg = handleRequest(HttpMethod.Get, "/orgs/$orgId") {
                addAuthorizationHeader("root")
                addHeader("If-Match", '"' + UUID.randomUUID().toString() + '"')
            }
            assertEquals(HttpStatusCode.PreconditionFailed, getOrg.response.status(), "Precondition Failed")
        }
    }

    @Test
    fun createAndReadWithIfNoneMatchSameEtag() {
        val orgId = "testCreateAndReadOrg";
        val orgName = "OpenMBEE";

        val etag = doCreateOrg(orgId, orgName).response.headers["ETag"]
        // If-None-Match same etag
        withTestEnvironment {
            val getOrg = handleRequest(HttpMethod.Get, "/orgs/$orgId") {
                addAuthorizationHeader("root")
                addHeader("If-None-Match", "\"$etag\"")
            }
            // Expect 304 Not Modified
            assertTrue(getOrg.response.status()?.isSuccess() ?: false, "Success with If-None-Match same etag ${getOrg.response.status()}")
        }
    }

    @Test
    fun createAndDeleteOrg() {
        val orgId = "testCreateAndReadOrg";
        val orgName = "OpenMBEE";

        doCreateOrg(orgId, orgName)

        // Delete
        withTestEnvironment {
            val delete = handleRequest(HttpMethod.Delete, "/orgs/$orgId") {
                addAuthorizationHeader("root")
            }
            assertTrue(delete.response.status()?.isSuccess() ?: false, "DELETE worked")
        }

        // Get deleted org should fail
        withTestEnvironment {
            val getOrg = handleRequest(HttpMethod.Get, "/orgs/$orgId") {
                addAuthorizationHeader("root")
            }
            Assertions.assertFalse(getOrg.response.status()?.isSuccess() ?: false, "Getting deleted org fails")
        }
    }

    //@Test
    fun testReadNonExistentOrg() {
        withTestEnvironment {
            val getOrg = handleRequest(HttpMethod.Get, "/orgs/testReadNonExistentOrg") {
                addAuthorizationHeader("root")
            }
            assertEquals(HttpStatusCode.NotFound, getOrg.response.status(), "Non existent org not found")
        }
    }

    //@Test
    fun testUpdateOrg() {
        val orgId = "testUpdateOrg";
        val orgName = "OpenMBEE testUpdateOrg";

        doCreateOrg(orgId, orgName)

        withTestEnvironment {
            handleRequest(HttpMethod.Patch, "/orgs/$orgId") {
                addAuthorizationHeader("root")
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
            val get = handleRequest(HttpMethod.Get, "/orgs/$orgId") {
                addAuthorizationHeader("root")
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
                addAuthorizationHeader("root")
            }
            assertTrue(get.response.status()?.isSuccess() ?: false, "Get /orgs success")
        }
    }
}
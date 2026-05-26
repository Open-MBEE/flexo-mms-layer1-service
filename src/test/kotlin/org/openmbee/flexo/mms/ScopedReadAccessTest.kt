package org.openmbee.flexo.mms

import io.kotest.assertions.ktor.client.shouldHaveStatus
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.ApplicationTestBuilder
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.rdf.model.Resource
import org.apache.jena.sparql.exec.http.UpdateExecutionHTTP
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.util.*

/**
 * Tests that listing all orgs/repos returns only resources the requesting user
 * actually has read access to via per-resource-scoped policies, rather than
 * requiring broad cluster/org-level scope checks.
 */
class ScopedReadAccessTest : OrgAny() {

    private val testUsername = "bob"
    private val testUserAuth = AuthStruct(testUsername)

    private val orgAId = "org-a"
    private val orgAName = "Org A"
    private val orgAPath = "/orgs/$orgAId"

    private val orgBId = "org-b"
    private val orgBName = "Org B"
    private val orgBPath = "/orgs/$orgBId"

    private val repoAId = "repo-a"
    private val repoAName = "Repo A"
    private val repoBId = "repo-b"
    private val repoBName = "Repo B"

    private val collectionAId = "collection-a"
    private val collectionBId = "collection-b"

    /**
     * Insert a user into the AccessControl.Agents graph via direct SPARQL Update.
     */
    private fun insertUser(username: String) {
        UpdateExecutionHTTP.service(backend.getUpdateUrl()).update("""
            PREFIX m-graph: <$ROOT_CONTEXT/graphs/>
            PREFIX m-user: <$ROOT_CONTEXT/users/>
            PREFIX mms: <https://mms.openmbee.org/rdf/ontology/>
            INSERT DATA {
                GRAPH m-graph:AccessControl.Agents {
                    m-user:$username a mms:User ;
                        mms:id "$username" .
                }
            }
        """.trimIndent()).execute()
    }

    /**
     * Create a policy (as root) that grants the given roles to a user on a specific scope.
     */
    private suspend fun ApplicationTestBuilder.createScopedPolicy(
        policyId: String,
        userPath: String,
        scopePath: String,
        roles: List<Resource>,
    ): HttpResponse {
        return httpPut("/policies/$policyId", true) {
            setTurtleBody(withAllTestPrefixes("""
                <>
                    mms:subject <${localIri(userPath)}> ;
                    mms:scope <${localIri(scopePath)}> ;
                    mms:role ${roles.joinToString(", ") { "<${it.uri}>" }} ;
                    .
            """.trimIndent()))
        }.apply {
            this shouldHaveStatus HttpStatusCode.Created
        }
    }

    /**
     * Make a GET request authenticated as the given user.
     */
    private suspend fun ApplicationTestBuilder.httpGetAs(
        auth: AuthStruct,
        uri: String,
    ): HttpResponse {
        return client.request {
            method = HttpMethod.Get
            url(uri)
            header("Authorization", authorization(auth))
        }
    }

    /**
     * Count the number of org resources (subjects with rdf:type mms:Org) in a Turtle response.
     */
    private suspend fun countOrgsInResponse(response: HttpResponse): Set<String> {
        val model = ModelFactory.createDefaultModel()
        parseTurtle(response.bodyAsText(), model, "http://test")
        val orgType = model.createResource(MMS.Org.uri)
        return model.listSubjectsWithProperty(RDF.type, orgType)
            .toList()
            .map { it.uri }
            .toSet()
    }

    /**
     * Count the number of collection resources (subjects with rdf:type mms:Collection) in a Turtle response.
     */
    private suspend fun countCollectionsInResponse(response: HttpResponse): Set<String> {
        val model = ModelFactory.createDefaultModel()
        parseTurtle(response.bodyAsText(), model, "http://test")
        val collectionType = model.createResource(MMS.Collection.uri)
        return model.listSubjectsWithProperty(RDF.type, collectionType)
            .toList()
            .map { it.uri }
            .toSet()
    }

    /**
     * Count the number of repo resources (subjects with rdf:type mms:Repo) in a Turtle response.
     */
    private suspend fun countReposInResponse(response: HttpResponse): Set<String> {
        val model = ModelFactory.createDefaultModel()
        parseTurtle(response.bodyAsText(), model, "http://test")
        val repoType = model.createResource(MMS.Repo.uri)
        return model.listSubjectsWithProperty(RDF.type, repoType)
            .toList()
            .map { it.uri }
            .toSet()
    }

    init {
        "list all orgs - user with org-scoped ReadOrg sees only that org" {
            // insert the test user into the agents graph
            insertUser(testUsername)

            testApplication {
                // create two orgs as root
                createOrg(orgAId, orgAName)
                createOrg(orgBId, orgBName)

                // grant bob ReadOrg on org-a only
                createScopedPolicy(
                    policyId = "BobReadOrgA",
                    userPath = "/users/$testUsername",
                    scopePath = orgAPath,
                    roles = listOf(MMS_OBJECT.ROLE.ReadOrg),
                )

                // bob lists all orgs — should see only org-a
                val bobResponse = httpGetAs(testUserAuth, "/orgs")
                bobResponse shouldHaveStatus HttpStatusCode.OK

                val bobOrgs = countOrgsInResponse(bobResponse)
                withClue("Bob should see exactly 1 org (org-a)") {
                    bobOrgs.size shouldBe 1
                }
                withClue("Bob's visible org should be org-a") {
                    bobOrgs.first() shouldBe localIri(orgAPath)
                }

                // root still sees both orgs (regression)
                val rootResponse = httpGetAs(rootAuth, "/orgs")
                rootResponse shouldHaveStatus HttpStatusCode.OK

                val rootOrgs = countOrgsInResponse(rootResponse)
                withClue("Root should see at least 2 orgs") {
                    (rootOrgs.size >= 2) shouldBe true
                }
                withClue("Root should see org-a") {
                    rootOrgs.contains(localIri(orgAPath)) shouldBe true
                }
                withClue("Root should see org-b") {
                    rootOrgs.contains(localIri(orgBPath)) shouldBe true
                }
            }
        }

        "list all orgs - user with no ReadOrg sees no orgs" {
            // insert the test user into the agents graph (no policies)
            insertUser(testUsername)

            testApplication {
                // create orgs as root
                createOrg(orgAId, orgAName)
                createOrg(orgBId, orgBName)

                // bob has no ReadOrg policy at all — should see no orgs
                val bobResponse = httpGetAs(testUserAuth, "/orgs")
                // may return 200 with empty results or 403
                if (bobResponse.status == HttpStatusCode.OK) {
                    val bobOrgs = countOrgsInResponse(bobResponse)
                    withClue("Bob should see 0 orgs") {
                        bobOrgs.size shouldBe 0
                    }
                }
            }
        }

        "list all orgs - user with cluster-level ReadOrg sees all orgs" {
            insertUser(testUsername)

            testApplication {
                createOrg(orgAId, orgAName)
                createOrg(orgBId, orgBName)

                // grant bob cluster-level ReadOrg
                createScopedPolicy(
                    policyId = "BobReadOrgCluster",
                    userPath = "/users/$testUsername",
                    scopePath = "/",
                    roles = listOf(MMS_OBJECT.ROLE.ReadOrg),
                )

                val bobResponse = httpGetAs(testUserAuth, "/orgs")
                bobResponse shouldHaveStatus HttpStatusCode.OK

                val bobOrgs = countOrgsInResponse(bobResponse)
                withClue("Bob should see both orgs with cluster-level ReadOrg") {
                    (bobOrgs.size >= 2) shouldBe true
                }
                withClue("Bob should see org-a") {
                    bobOrgs.contains(localIri(orgAPath)) shouldBe true
                }
                withClue("Bob should see org-b") {
                    bobOrgs.contains(localIri(orgBPath)) shouldBe true
                }
            }
        }

        "list all repos - user with repo-scoped ReadRepo sees only that repo" {
            insertUser(testUsername)

            testApplication {
                // create org and two repos as root
                createOrg(orgAId, orgAName)
                createRepo(orgAPath, repoAId, repoAName)
                createRepo(orgAPath, repoBId, repoBName)

                val repoAPath = "$orgAPath/repos/$repoAId"
                val repoBPath = "$orgAPath/repos/$repoBId"

                // grant bob ReadRepo on repo-a only
                createScopedPolicy(
                    policyId = "BobReadRepoA",
                    userPath = "/users/$testUsername",
                    scopePath = repoAPath,
                    roles = listOf(MMS_OBJECT.ROLE.ReadRepo),
                )

                // bob lists all repos — should see only repo-a
                val bobResponse = httpGetAs(testUserAuth, "$orgAPath/repos")
                bobResponse shouldHaveStatus HttpStatusCode.OK

                val bobRepos = countReposInResponse(bobResponse)
                withClue("Bob should see exactly 1 repo (repo-a)") {
                    bobRepos.size shouldBe 1
                }
                withClue("Bob's visible repo should be repo-a") {
                    bobRepos.first() shouldBe localIri(repoAPath)
                }

                // root still sees both repos (regression)
                val rootResponse = httpGetAs(rootAuth, "$orgAPath/repos")
                rootResponse shouldHaveStatus HttpStatusCode.OK

                val rootRepos = countReposInResponse(rootResponse)
                withClue("Root should see at least 2 repos") {
                    (rootRepos.size >= 2) shouldBe true
                }
                withClue("Root should see repo-a") {
                    rootRepos.contains(localIri(repoAPath)) shouldBe true
                }
                withClue("Root should see repo-b") {
                    rootRepos.contains(localIri(repoBPath)) shouldBe true
                }
            }
        }

        "list all repos - user with no ReadRepo sees no repos" {
            insertUser(testUsername)

            testApplication {
                createOrg(orgAId, orgAName)
                createRepo(orgAPath, repoAId, repoAName)
                createRepo(orgAPath, repoBId, repoBName)

                // bob has no ReadRepo policy — should see no repos
                val bobResponse = httpGetAs(testUserAuth, "$orgAPath/repos")
                if (bobResponse.status == HttpStatusCode.OK) {
                    val bobRepos = countReposInResponse(bobResponse)
                    withClue("Bob should see 0 repos") {
                        bobRepos.size shouldBe 0
                    }
                }
            }
        }

        "list all repos - user with org-scoped ReadRepo sees all repos in that org" {
            insertUser(testUsername)

            testApplication {
                createOrg(orgAId, orgAName)
                createRepo(orgAPath, repoAId, repoAName)
                createRepo(orgAPath, repoBId, repoBName)

                val repoAPath = "$orgAPath/repos/$repoAId"
                val repoBPath = "$orgAPath/repos/$repoBId"

                // grant bob org-level ReadRepo
                createScopedPolicy(
                    policyId = "BobReadRepoOrgA",
                    userPath = "/users/$testUsername",
                    scopePath = orgAPath,
                    roles = listOf(MMS_OBJECT.ROLE.ReadRepo),
                )

                val bobResponse = httpGetAs(testUserAuth, "$orgAPath/repos")
                bobResponse shouldHaveStatus HttpStatusCode.OK

                val bobRepos = countReposInResponse(bobResponse)
                withClue("Bob should see both repos with org-level ReadRepo") {
                    (bobRepos.size >= 2) shouldBe true
                }
                withClue("Bob should see repo-a") {
                    bobRepos.contains(localIri(repoAPath)) shouldBe true
                }
                withClue("Bob should see repo-b") {
                    bobRepos.contains(localIri(repoBPath)) shouldBe true
                }
            }
        }

        "list all collections - user with collection-scoped ReadCollection sees only that collection" {
            insertUser(testUsername)

            testApplication {
                // create org + repo (collections need a branch to collect)
                createOrg(orgAId, orgAName)
                createRepo(orgAPath, repoAId, repoAName)

                val branchRef = "$orgAPath/repos/$repoAId/branches/master"
                val collectionAPath = "$orgAPath/collections/$collectionAId"
                val collectionBPath = "$orgAPath/collections/$collectionBId"

                // create two collections as root
                createCollection(orgAPath, collectionAId, withAllTestPrefixes("""
                    <> dct:title "Collection A"@en .
                    <> mms:collects <${localIri(branchRef)}> .
                """.trimIndent()))
                createCollection(orgAPath, collectionBId, withAllTestPrefixes("""
                    <> dct:title "Collection B"@en .
                    <> mms:collects <${localIri(branchRef)}> .
                """.trimIndent()))

                // grant bob ReadCollection on collection-a only
                createScopedPolicy(
                    policyId = "BobReadCollectionA",
                    userPath = "/users/$testUsername",
                    scopePath = collectionAPath,
                    roles = listOf(MMS_OBJECT.ROLE.ReadCollection),
                )

                // bob lists all collections — should see only collection-a
                val bobResponse = httpGetAs(testUserAuth, "$orgAPath/collections")
                bobResponse shouldHaveStatus HttpStatusCode.OK

                val bobCollections = countCollectionsInResponse(bobResponse)
                withClue("Bob should see exactly 1 collection (collection-a)") {
                    bobCollections.size shouldBe 1
                }
                withClue("Bob's visible collection should be collection-a") {
                    bobCollections.first() shouldBe localIri(collectionAPath)
                }

                // root still sees both collections (regression)
                val rootResponse = httpGetAs(rootAuth, "$orgAPath/collections")
                rootResponse shouldHaveStatus HttpStatusCode.OK

                val rootCollections = countCollectionsInResponse(rootResponse)
                withClue("Root should see at least 2 collections") {
                    (rootCollections.size >= 2) shouldBe true
                }
                withClue("Root should see collection-a") {
                    rootCollections.contains(localIri(collectionAPath)) shouldBe true
                }
                withClue("Root should see collection-b") {
                    rootCollections.contains(localIri(collectionBPath)) shouldBe true
                }
            }
        }

        "list all collections - user with no ReadCollection sees no collections" {
            insertUser(testUsername)

            testApplication {
                createOrg(orgAId, orgAName)
                createRepo(orgAPath, repoAId, repoAName)

                val branchRef = "$orgAPath/repos/$repoAId/branches/master"

                createCollection(orgAPath, collectionAId, withAllTestPrefixes("""
                    <> dct:title "Collection A"@en .
                    <> mms:collects <${localIri(branchRef)}> .
                """.trimIndent()))

                // bob has no ReadCollection policy — should see no collections
                val bobResponse = httpGetAs(testUserAuth, "$orgAPath/collections")
                if (bobResponse.status == HttpStatusCode.OK) {
                    val bobCollections = countCollectionsInResponse(bobResponse)
                    withClue("Bob should see 0 collections") {
                        bobCollections.size shouldBe 0
                    }
                }
            }
        }

        "list all collections - user with org-scoped ReadCollection sees all collections in that org" {
            insertUser(testUsername)

            testApplication {
                createOrg(orgAId, orgAName)
                createRepo(orgAPath, repoAId, repoAName)

                val branchRef = "$orgAPath/repos/$repoAId/branches/master"
                val collectionAPath = "$orgAPath/collections/$collectionAId"
                val collectionBPath = "$orgAPath/collections/$collectionBId"

                createCollection(orgAPath, collectionAId, withAllTestPrefixes("""
                    <> dct:title "Collection A"@en .
                    <> mms:collects <${localIri(branchRef)}> .
                """.trimIndent()))
                createCollection(orgAPath, collectionBId, withAllTestPrefixes("""
                    <> dct:title "Collection B"@en .
                    <> mms:collects <${localIri(branchRef)}> .
                """.trimIndent()))

                // grant bob org-level ReadCollection
                createScopedPolicy(
                    policyId = "BobReadCollectionOrgA",
                    userPath = "/users/$testUsername",
                    scopePath = orgAPath,
                    roles = listOf(MMS_OBJECT.ROLE.ReadCollection),
                )

                val bobResponse = httpGetAs(testUserAuth, "$orgAPath/collections")
                bobResponse shouldHaveStatus HttpStatusCode.OK

                val bobCollections = countCollectionsInResponse(bobResponse)
                withClue("Bob should see both collections with org-level ReadCollection") {
                    (bobCollections.size >= 2) shouldBe true
                }
                withClue("Bob should see collection-a") {
                    bobCollections.contains(localIri(collectionAPath)) shouldBe true
                }
                withClue("Bob should see collection-b") {
                    bobCollections.contains(localIri(collectionBPath)) shouldBe true
                }
            }
        }
    }
}

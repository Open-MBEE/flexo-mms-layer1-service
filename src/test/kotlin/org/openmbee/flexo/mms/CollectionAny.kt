package org.openmbee.flexo.mms

import io.kotest.core.test.TestCase
import io.ktor.server.testing.testApplication
import org.openmbee.flexo.mms.util.createRepo
import org.slf4j.LoggerFactory

open class CollectionAny : RepoAny() {
    override val logger = LoggerFactory.getLogger(CollectionAny::class.java)

    val basePathCollections = "$demoOrgPath/collections"

    val demoCollectionId = "test-collection"
    val demoCollectionName = "Test Collection"
    val demoCollectionPath = "$basePathCollections/$demoCollectionId"

    // collections need a repo with a branch to collect
    val demoBranchRef = "$demoRepoPath/branches/master"

    val validCollectionBody = """
        <> dct:title "$demoCollectionName"@en .
        <> mms:collects <$demoBranchRef> .
    """.trimIndent()

    val masterBranchPath = "$demoRepoPath/branches/master"
    // a second repo for cross-repo collection tests
    val secondRepoId = "second-repo"
    val secondRepoName = "Second Repo"
    val secondRepoPath = "$demoOrgPath/repos/$secondRepoId"
    val secondMasterBranchPath = "$secondRepoPath/branches/master"
    val secondLockId = "lock-1"
    val secondLockPath = "$secondRepoPath/locks/$secondLockId"

    val collectionLockId = "demo-lock"
    val collectionLockPath = "$demoRepoPath/locks/$collectionLockId"

    val scratchId = "scratch-1"
    val scratchPath = "$demoRepoPath/scratches/$scratchId"

    // insert Alice into first repo's master branch
    val insertAlice = """
        $demoPrefixesStr

        insert data {
            :Alice a :Person ;
                foaf:name "Alice" ;
                .
        }
    """.trimIndent()

    // insert Bob into second repo's master branch
    val insertBob = """
        $demoPrefixesStr

        insert data {
            :Bob a :Person ;
                foaf:name "Bob" ;
                .
        }
    """.trimIndent()

    // insert Charlie into scratch
    val insertCharlie = """
        $demoPrefixesStr

        insert data {
            :Charlie a :Person ;
                foaf:name "Charlie" ;
                .
        }
    """.trimIndent()

    // query to select all person names, ordered
    val queryPersonNames = """
        $demoPrefixesStr

        select ?name where {
            ?s a :Person .
            ?s foaf:name ?name .
        } order by asc(?name)
    """.trimIndent()

    // ASK query for Alice
    val askAlice = """
        $demoPrefixesStr

        ask {
            :Alice a :Person .
        }
    """.trimIndent()

    // CONSTRUCT query for all persons
    val constructPersons = """
        $demoPrefixesStr

        construct {
            ?s a :Person ;
                foaf:name ?name .
        } where {
            ?s a :Person ;
                foaf:name ?name .
        }
    """.trimIndent()

    override suspend fun beforeEach(testCase: TestCase) {
        super.beforeEach(testCase)
        testApplication {
            // create repo so we have a branch to collect
            createRepo(demoOrgPath, demoRepoId, demoRepoName)
        }
    }
}
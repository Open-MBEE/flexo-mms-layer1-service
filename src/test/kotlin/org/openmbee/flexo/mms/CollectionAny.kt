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

    val fooCollectionId = "foo-collection"
    val fooCollectionName = "Foo Collection"
    val fooCollectionPath = "$demoOrgPath/collections/$fooCollectionId"

    val barCollectionId = "bar-collection"
    val barCollectionName = "Bar Collection"
    val barCollectionPath = "$demoOrgPath/collections/$barCollectionId"

    // collections need a repo with a branch to collect
    val demoBranchRef = "$demoRepoPath/branches/master"

    val validCollectionBody = """
        <> dct:title "$demoCollectionName"@en .
        <> mms:collects <$demoBranchRef> .
    """.trimIndent()

    override suspend fun beforeEach(testCase: TestCase) {
        super.beforeEach(testCase)
        testApplication {
            // create repo so we have a branch to collect
            createRepo(demoOrgPath, demoRepoId, demoRepoName)
        }
    }
}
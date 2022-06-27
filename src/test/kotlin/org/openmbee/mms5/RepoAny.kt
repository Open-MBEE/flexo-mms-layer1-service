package org.openmbee.mms5

import io.kotest.core.test.TestCase
import org.openmbee.mms5.util.CommonSpec
import org.openmbee.mms5.util.createOrg
import org.slf4j.LoggerFactory

open class RepoAny : CommonSpec() {
    val logger = LoggerFactory.getLogger(RepoUpdate::class.java)

    val orgId = "base-org"
    val orgPath = "/orgs/$orgId"
    val orgName = "Base Org"

    val repoId = "new-repo"
    val repoName = "New Repo"
    val repoPath = "$orgPath/repos/$repoId"

    val arbitraryPropertyIri = "https://demo.org/custom/prop"
    val arbitraryPropertyValue = "test"

    val validRepoBody = """
        <> dct:title "$repoName"@en .
    """.trimIndent()

    // create an org before each repo test
    override suspend fun beforeEach(testCase: TestCase) {
        super.beforeEach(testCase)

        logger.info("Creating org $orgId")

        createOrg(orgId, orgName)

        logger.info("done")
    }
}
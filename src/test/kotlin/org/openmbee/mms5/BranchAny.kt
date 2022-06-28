package org.openmbee.mms5

import io.kotest.core.test.TestCase
import org.openmbee.mms5.util.CommonSpec
import org.openmbee.mms5.util.createOrg
import org.openmbee.mms5.util.createRepo
import org.slf4j.LoggerFactory

open class BranchAny : CommonSpec() {
    val logger = LoggerFactory.getLogger(RepoUpdate::class.java)

    val orgId = "base-org"
    val orgPath = "/orgs/$orgId"
    val orgName = "Base Org"

    val repoId = "new-repo"
    val repoName = "New Repo"
    val repoPath = "$orgPath/repos/$repoId"

    val branchId = "new-branch"
    val branchName = "New Branch"
    val branchPath = "$repoPath/branches/$branchId"
    val arbitraryPropertyIri = "https://demo.org/custom/prop"
    val arbitraryPropertyValue = "test"

    val validBranchBody = """
        <> dct:title "$branchName"@en .
        <> mms:ref <./master> .
    """.trimIndent()

    // create a repo before each branch test
    override suspend fun beforeEach(testCase: TestCase) {
        super.beforeEach(testCase)

        logger.info("Creating org $orgId and repo $repoId")

        createOrg(orgId, orgName)
        createRepo(repoId, repoName, orgId)
        logger.info("done")
    }
}

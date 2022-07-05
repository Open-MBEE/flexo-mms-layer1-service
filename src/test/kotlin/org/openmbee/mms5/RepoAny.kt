package org.openmbee.mms5

import io.kotest.core.test.TestCase
import org.apache.jena.http.HttpOp.httpGet
import org.openmbee.mms5.util.CommonSpec
import org.openmbee.mms5.util.createOrg
import org.slf4j.LoggerFactory

open class RepoAny : OrgAny() {
    override val logger = LoggerFactory.getLogger(RepoUpdate::class.java)

    val repoId = "new-repo"
    val repoName = "New Repo"
    val repoPath = "$orgPath/repos/$repoId"
    val commitsPath = "$repoPath/commits"
    val validRepoBody = """
        <> dct:title "$repoName"@en .
    """.trimIndent()

    // create an org before each repo test
    override suspend fun beforeEach(testCase: TestCase) {
        super.beforeEach(testCase)

        // create base org for repo test
        createOrg(orgId, orgName)
    }
}
package org.openmbee.mms5

import io.kotest.core.test.TestCase
import org.openmbee.mms5.util.createRepo

open class RefAny : RepoAny() {

    // create an org before each repo test
    override suspend fun beforeEach(testCase: TestCase) {
        super.beforeEach(testCase)

        // create base org for repo test
        createRepo(repoId, repoName)
    }
}
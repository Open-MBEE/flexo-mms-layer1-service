package org.openmbee.flexo.mms

import io.kotest.core.test.TestCase
import org.slf4j.LoggerFactory

open class CommitAny : RefAny() {
    val basePathCommits = "$demoRepoPath/commits"
    override val logger = LoggerFactory.getLogger(CommitAny::class.java)
    override suspend fun beforeEach(testCase: TestCase) {
        super.beforeEach(testCase)
    }
}

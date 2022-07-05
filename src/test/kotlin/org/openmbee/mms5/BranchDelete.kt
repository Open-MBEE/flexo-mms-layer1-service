package org.openmbee.mms5

import io.kotest.assertions.ktor.shouldHaveStatus
import io.ktor.http.*
import org.openmbee.mms5.util.httpDelete
import org.openmbee.mms5.util.httpGet
import org.openmbee.mms5.util.withTest
import org.openmbee.mms5.util.*
class BranchDelete : RefAny() {
    init {
        "delete branch" {
            createBranch(branchId, branchName, "master", repoId, orgId)

            withTest {
                // delete branch should work
                httpDelete(branchPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                }

                // get deleted branch should 404
                httpGet(branchPath) {}.apply {
                    response shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }
    }
}

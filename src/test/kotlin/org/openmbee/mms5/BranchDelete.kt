package org.openmbee.mms5


import io.ktor.http.*
import org.openmbee.mms5.util.httpDelete
import org.openmbee.mms5.util.httpGet
import org.openmbee.mms5.util.withTest
import org.openmbee.mms5.util.*
class BranchDelete : RefAny() {
    init {
        "delete branch" {
            createBranch(repoPath, "master", branchId, branchName)

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

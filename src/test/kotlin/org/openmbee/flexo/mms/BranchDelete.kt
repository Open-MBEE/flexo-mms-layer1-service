package org.openmbee.flexo.mms


import io.ktor.http.*
import org.openmbee.flexo.mms.util.httpDelete
import org.openmbee.flexo.mms.util.httpGet
import org.openmbee.flexo.mms.util.withTest
import org.openmbee.flexo.mms.util.*
class BranchDelete : RefAny() {
    init {
        "delete branch".config(enabled=false) {
            createBranch(demoRepoPath, "master", branchId, branchName)

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

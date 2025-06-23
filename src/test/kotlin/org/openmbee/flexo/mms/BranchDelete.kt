package org.openmbee.flexo.mms


import io.kotest.assertions.ktor.client.shouldHaveStatus
import io.ktor.http.*
import io.ktor.server.testing.*
import org.openmbee.flexo.mms.util.httpDelete
import org.openmbee.flexo.mms.util.httpGet
import org.openmbee.flexo.mms.util.*
class BranchDelete : RefAny() {
    init {
        "delete branch".config(enabled=false) {
            testApplication {
                createBranch(demoRepoPath, "master", demoBranchId, demoBranchName)
                // delete branch should work
                httpDelete(demoBranchPath) {}.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                }

                // get deleted branch should 404
                httpGet(demoBranchPath) {}.apply {
                    this shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }
    }
}

package org.openmbee.mms5.routes.endpoints

import io.ktor.application.*
import io.ktor.routing.*
import org.openmbee.mms5.BRANCH_QUERY_CONDITIONS
import org.openmbee.mms5.Permission
import org.openmbee.mms5.mmsL1
import org.openmbee.mms5.queryModel


fun Route.queryModel() {
    post("/orgs/{orgId}/repos/{repoId}/branches/{branchId}/query/{inspect?}") {
        call.mmsL1(Permission.READ_BRANCH) {
            pathParams {
                org()
                repo()
                branch()
                inspect()
            }

            // auto-inject default prefixes
            val inputQueryString = "$prefixes\n$requestBody"

            queryModel(inputQueryString, prefixes["morb"]!!, BRANCH_QUERY_CONDITIONS.append {
                assertPreconditions(this) { "" }
            })
        }

    }
}

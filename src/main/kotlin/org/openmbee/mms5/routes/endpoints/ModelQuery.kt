package org.openmbee.mms5.routes.endpoints

import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.apache.jena.query.QueryFactory
import org.openmbee.mms5.*


fun Route.queryModel() {
    post("/orgs/{orgId}/repos/{repoId}/branches/{branchId}/query/{inspect?}") {
        call.mmsL1(Permission.READ_BRANCH) {
            pathParams {
                org()
                repo()
                branch()
                inspect()
            }

            checkPrefixConflicts()

            // auto-inject default prefixes
            val inputQueryString = "$prefixes\n$requestBody"

            queryModel(inputQueryString, prefixes["morb"]!!, BRANCH_QUERY_CONDITIONS.append {
                assertPreconditions(this) { "" }
            })
        }
    }
}

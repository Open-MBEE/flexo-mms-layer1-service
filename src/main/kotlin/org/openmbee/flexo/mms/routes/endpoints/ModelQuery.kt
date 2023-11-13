package org.openmbee.flexo.mms.routes.endpoints

import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.openmbee.flexo.mms.*


fun Route.queryModel() {
    post("/orgs/{orgId}/repos/{repoId}/branches/{branchId}/query/{inspect?}") {
        call.mmsL1(Permission.READ_BRANCH) {
            pathParams {
                org()
                repo()
                branch()
                inspect()
            }

            //checkPrefixConflicts()

            // use request body for SPARQL query
            val inputQueryString = requestBody

            processAndSubmitUserQuery(inputQueryString, prefixes["morb"]!!, BRANCH_QUERY_CONDITIONS.append {
                assertPreconditions(this) { "" }
            })
        }
    }
}

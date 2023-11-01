package org.openmbee.mms5.routes.endpoints

import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.openmbee.mms5.DIFF_QUERY_CONDITIONS
import org.openmbee.mms5.Permission
import org.openmbee.mms5.mmsL1
import org.openmbee.mms5.queryModel


fun Route.queryDiff() {
    post("/orgs/{orgId}/repos/{repoId}/diff/{diffId}/query/{inspect?}") {
        call.mmsL1(Permission.READ_DIFF) {
            pathParams {
                org()
                repo()
                diff()
                inspect()
            }

            checkPrefixConflicts()

            // use request body for SPARQL query
            val inputQueryString = requestBody

            queryModel(inputQueryString, prefixes["mord"]!!, DIFF_QUERY_CONDITIONS.append {
                assertPreconditions(this) { "" }
            })
        }
    }
}

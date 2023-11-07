package org.openmbee.flexo.mms.routes.endpoints

import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.openmbee.flexo.mms.DIFF_QUERY_CONDITIONS
import org.openmbee.flexo.mms.Permission
import org.openmbee.flexo.mms.mmsL1
import org.openmbee.flexo.mms.processAndSubmitUserQuery


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

            processAndSubmitUserQuery(inputQueryString, prefixes["mord"]!!, DIFF_QUERY_CONDITIONS.append {
                assertPreconditions(this) { "" }
            })
        }
    }
}

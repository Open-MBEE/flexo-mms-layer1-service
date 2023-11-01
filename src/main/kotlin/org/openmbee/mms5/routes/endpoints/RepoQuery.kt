package org.openmbee.mms5.routes.endpoints

import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.openmbee.mms5.*


fun Route.queryRepo() {
    post("/orgs/{orgId}/repos/{repoId}/query/{inspect?}") {
        call.mmsL1(Permission.READ_REPO) {
            pathParams {
                org()
                repo()
                inspect()
            }

            checkPrefixConflicts()

            // use request body for SPARQL query
            val inputQueryString = requestBody

            processAndSubmitUserQuery(inputQueryString, prefixes["mor"]!!, REPO_QUERY_CONDITIONS.append {
                assertPreconditions(this) { "" }
            }, true, prefixes["mor"])
        }
    }
}

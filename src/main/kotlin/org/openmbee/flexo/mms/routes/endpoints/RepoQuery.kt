package org.openmbee.flexo.mms.routes.endpoints

import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.openmbee.flexo.mms.*


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
            val inputQueryString = "$prefixes\n$requestBody"

            processAndSubmitUserQuery(inputQueryString, prefixes["mor"]!!, REPO_QUERY_CONDITIONS.append {
                assertPreconditions(this) { "" }
            }, false, prefixes["mor"])
        }
    }
}

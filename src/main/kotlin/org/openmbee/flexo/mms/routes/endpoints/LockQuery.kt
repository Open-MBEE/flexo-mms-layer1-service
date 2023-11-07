package org.openmbee.flexo.mms.routes.endpoints

import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.openmbee.flexo.mms.*

fun Route.queryLock() {
    post("/orgs/{orgId}/repos/{repoId}/locks/{lockId}/query/{inspect?}") {
        call.mmsL1(Permission.READ_LOCK) {
            pathParams {
                org()
                repo()
                lock()
                inspect()
            }

            //checkPrefixConflicts()

            // use request body for SPARQL query
            val inputQueryString = requestBody

            processAndSubmitUserQuery(inputQueryString, prefixes["morl"]!!, LOCK_QUERY_CONDITIONS.append {
                assertPreconditions(this) { "" }
            })
        }
    }
}

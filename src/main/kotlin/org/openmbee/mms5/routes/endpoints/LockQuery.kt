package org.openmbee.mms5.routes.endpoints

import io.ktor.application.*
import io.ktor.routing.*
import org.openmbee.mms5.*

fun Route.queryLock() {
    post("/orgs/{orgId}/repos/{repoId}/locks/{lockId}/query/{inspect?}") {
        call.mmsL1(Permission.READ_LOCK) {
            pathParams {
                org()
                repo()
                lock()
                inspect()
            }

            // auto-inject default prefixes
            val inputQueryString = "$prefixes\n$requestBody"

            queryModel(inputQueryString, prefixes["morl"]!!, LOCK_QUERY_CONDITIONS.append {
                assertPreconditions(this) { "" }
            })
        }
    }
}

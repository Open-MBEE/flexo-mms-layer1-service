package org.openmbee.mms5.routes.endpoints

import io.ktor.application.*
import io.ktor.routing.*
import org.openmbee.mms5.Permission
import org.openmbee.mms5.mmsL1
import org.openmbee.mms5.queryModel


fun Route.queryLock() {
    post("/orgs/{orgId}/repos/{repoId}/locks/{lockId}/query/{inspect?}") {
        call.mmsL1(Permission.READ_LOCK) {
            pathParams {
                org()
                repo()
                lock()
                inspect()
            }

            queryModel(requestBody, prefixes["morl"]!!)
        }
    }
}

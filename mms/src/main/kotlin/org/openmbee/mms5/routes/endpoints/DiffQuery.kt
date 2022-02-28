package org.openmbee.mms5.routes.endpoints

import io.ktor.application.*
import io.ktor.routing.*
import org.openmbee.mms5.Permission
import org.openmbee.mms5.mmsL1
import org.openmbee.mms5.queryModel


fun Route.queryDiff() {
    post("/orgs/{orgId}/repos/{repoId}/commits/{commitId}/locks/{lockId}/diff/{diffId}/query/{inspect?}") {
        call.mmsL1(Permission.READ_DIFF) {
            pathParams {
                org()
                repo()
                commit()
                lock()
                diff()
                inspect()
            }

            queryModel(requestBody, prefixes["morcld"]!!)
        }
    }
}

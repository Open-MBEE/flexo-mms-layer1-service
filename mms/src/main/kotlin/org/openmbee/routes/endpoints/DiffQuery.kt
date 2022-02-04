package org.openmbee.routes.endpoints

import io.ktor.application.*
import io.ktor.routing.*
import io.ktor.util.*
import org.openmbee.Permission
import org.openmbee.mmsL1
import org.openmbee.queryModel


fun Application.queryDiff() {
    routing {
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
}

package org.openmbee.routes.endpoints

import io.ktor.application.*
import io.ktor.routing.*
import io.ktor.util.*
import org.openmbee.Permission
import org.openmbee.mmsL1
import org.openmbee.queryModel


fun Application.queryLock() {
    routing {
        post("/orgs/{orgId}/repos/{repoId}/commits/{commitId}/locks/{lockId}/query/{inspect?}") {
            call.mmsL1(Permission.READ_LOCK) {
                pathParams {
                    org()
                    repo()
                    commit()
                    lock()
                    inspect()
                }

                queryModel(requestBody, prefixes["morcl"]!!)
            }
        }
    }
}

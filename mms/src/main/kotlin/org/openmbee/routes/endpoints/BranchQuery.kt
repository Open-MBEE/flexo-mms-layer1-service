package org.openmbee.routes.endpoints

import io.ktor.application.*
import io.ktor.routing.*
import io.ktor.util.*
import org.openmbee.Permission
import org.openmbee.mmsL1
import org.openmbee.queryModel


fun Application.queryBranch() {
    routing {
        post("/orgs/{orgId}/repos/{repoId}/branches/{branchId}/query/{inspect?}") {
            call.mmsL1(Permission.READ_BRANCH) {
                pathParams {
                    org()
                    repo()
                    branch()
                    inspect()
                }

                queryModel(requestBody, prefixes["morb"]!!)
            }

        }
    }
}

package org.openmbee.mms5.routes

import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.openmbee.mms5.*


private val DEFAULT_UPDATE_CONDITIONS = REPO_CRUD_CONDITIONS.append {
    permit(Permission.UPDATE_REPO, Scope.REPO)
}


fun Route.updateRepo() {
    patch("/orgs/{orgId}/repos/{repoId}") {
        call.mmsL1(Permission.UPDATE_REPO) {
            pathParams {
                org()
                repo()
            }

            guardedPatch(
                objectKey = "mor",
                graph = "m-graph:Cluster",
                preconditions = DEFAULT_UPDATE_CONDITIONS,
            )
        }
    }
}

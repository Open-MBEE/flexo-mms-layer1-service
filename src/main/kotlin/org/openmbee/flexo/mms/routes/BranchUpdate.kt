package org.openmbee.flexo.mms.routes

import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.openmbee.flexo.mms.*


private val DEFAULT_UPDATE_CONDITIONS = ORG_CRUD_CONDITIONS.append {
    permit(Permission.UPDATE_BRANCH, Scope.BRANCH)
}


fun Route.updateBranch() {
    patch("/orgs/{orgId}/repos/{repoId}/branches/{branchId}") {
        call.mmsL1(Permission.UPDATE_BRANCH) {
            pathParams {
                org()
                repo()
                branch()
            }

            guardedPatch(
                objectKey = "morb",
                graph = "mor-graph:Metadata",
                preconditions = DEFAULT_UPDATE_CONDITIONS,
            )
        }
    }
}
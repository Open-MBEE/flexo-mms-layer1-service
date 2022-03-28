package org.openmbee.mms5.routes

import io.ktor.application.*
import io.ktor.routing.*
import org.openmbee.mms5.*


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
                conditions = DEFAULT_UPDATE_CONDITIONS,
            )
        }
    }
}
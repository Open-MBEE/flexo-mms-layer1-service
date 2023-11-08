package org.openmbee.flexo.mms.routes

import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.openmbee.flexo.mms.*


private val DEFAULT_UPDATE_CONDITIONS = ORG_CRUD_CONDITIONS.append {
    permit(Permission.UPDATE_ORG, Scope.ORG)
}


fun Route.updateOrg() {
    patch("/orgs/{orgId}") {
        call.mmsL1(Permission.UPDATE_ORG) {
            pathParams {
                org()
            }

            guardedPatch(
                objectKey = "mo",
                graph = "m-graph:Cluster",
                preconditions = DEFAULT_UPDATE_CONDITIONS,
            )
        }
    }
}

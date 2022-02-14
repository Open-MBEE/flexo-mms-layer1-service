package org.openmbee.mms5.routes

import io.ktor.application.*
import io.ktor.routing.*
import org.openmbee.mms5.*


private val DEFAULT_UPDATE_CONDITIONS = ORG_CRUD_CONDITIONS.append {
    permit(Permission.UPDATE_ORG, Scope.ORG)
}


fun Application.updateOrg() {
    routing {
        patch("/orgs/{orgId}") {
            call.mmsL1(Permission.UPDATE_ORG) {
                pathParams {
                    org()
                }

                guardedPatch(
                    objectKey = "mo",
                    graph = "m-graph:Cluster",
                    conditions = DEFAULT_UPDATE_CONDITIONS,
                )
            }
        }
    }
}
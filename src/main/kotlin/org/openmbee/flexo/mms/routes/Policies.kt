package org.openmbee.flexo.mms.routes

import io.ktor.server.routing.*
import org.openmbee.flexo.mms.routes.ldp.createOrReplacePolicy
import org.openmbee.flexo.mms.server.linkedDataPlatformDirectContainer

private const val POLICIES_PATH = "/policies"

/**
 * Policy CRUD routing
 */
fun Route.crudPolicies() {
    // all policies
    linkedDataPlatformDirectContainer(POLICIES_PATH) {
        // create new policy
        post { slug ->
            // set policy id on context
            policyId = slug

            // create new policy
            createOrReplacePolicy()
        }
    }

    // specific policy
    linkedDataPlatformDirectContainer("$POLICIES_PATH/{policyId}") {
        // create or replace policy
        put {
            createOrReplacePolicy()
        }
    }
}

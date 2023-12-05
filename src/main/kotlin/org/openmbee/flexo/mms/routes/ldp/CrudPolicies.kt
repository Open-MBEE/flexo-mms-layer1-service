package org.openmbee.flexo.mms.routes.ldp

import io.ktor.server.routing.*
import org.openmbee.flexo.mms.assertLegalId
import org.openmbee.flexo.mms.plugins.linkedDataPlatformDirectContainer

private const val POLICIES_PATH = "/policies"

/**
 * Policy CRUD routing
 */
fun Route.CrudPolicies() {
    // all policies
    linkedDataPlatformDirectContainer(POLICIES_PATH) {
        // create new policy
        post { slug ->
            // assert id is legal
            assertLegalId(slug)

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
            // assert id is legal when new resource is being created
            assertLegalId(policyId!!)

            // create/replace policy
            createOrReplacePolicy()
        }
    }
}

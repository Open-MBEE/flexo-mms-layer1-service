package org.openmbee.flexo.mms.routes

import io.ktor.server.routing.*
import org.openmbee.flexo.mms.LDAP_COMPATIBLE_SLUG_REGEX
import org.openmbee.flexo.mms.routes.ldp.createOrReplaceGroup
import org.openmbee.flexo.mms.routes.ldp.createOrReplaceOrg
import org.openmbee.flexo.mms.server.linkedDataPlatformDirectContainer

private const val GROUPS_PATH = "/groups"

/**
 * Group CRUD routing
 */
fun Route.crudGroups() {
    // all groups
    linkedDataPlatformDirectContainer(GROUPS_PATH) {
        legalSlugRegex = LDAP_COMPATIBLE_SLUG_REGEX

//        // state of all groups
//        head {
//            headGroups(true)
//        }
//
//        // read all groups
//        get {
//            getGroups(true)
//        }

        // create a new group
        post { slug ->
            // set group id on context
            groupId = slug

            // create new group
            createOrReplaceGroup()
        }
    }

    // specific group
    linkedDataPlatformDirectContainer("$GROUPS_PATH/{groupId}") {
        legalSlugRegex = LDAP_COMPATIBLE_SLUG_REGEX

        beforeEach = {
            parsePathParams {
                group()
            }
        }

//        // state of a group
//        head {
//            headGroups()
//        }
//
//        // read a group
//        get {
//            getGroups()
//        }

        // create or replace group
        put {
            createOrReplaceGroup()
        }

//        // modify existing group
//        patch {
////            guardedPatch(
////                updateRequest = it,
////                objectKey = "mo",
////                graph = "m-graph:Cluster",
////                preconditions = UPDATE_ORG_CONDITIONS,
////            )
//        }
    }
}

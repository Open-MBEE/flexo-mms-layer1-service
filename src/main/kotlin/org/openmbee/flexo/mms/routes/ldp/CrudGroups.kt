package org.openmbee.flexo.mms.routes.ldp

import io.ktor.server.routing.*
import org.openmbee.flexo.mms.assertLegalId
import org.openmbee.flexo.mms.plugins.linkedDataPlatformDirectContainer

private const val GROUPS_PATH = "/groups"

/**
 * Group CRUD routing
 */
fun Route.CrudGroups() {
    // all groups
    linkedDataPlatformDirectContainer(GROUPS_PATH) {
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
            // assert id is legal
            assertLegalId(slug)

            // set group id on context
            groupId = slug

            // create new group
            createOrReplaceOrg()
        }
    }

    // specific group
    linkedDataPlatformDirectContainer("$GROUPS_PATH/{groupId}") {
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
            // assert id is legal when new resource is being created
            assertLegalId(groupId!!)

            // create/replace group
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

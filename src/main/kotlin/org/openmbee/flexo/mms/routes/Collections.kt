package org.openmbee.flexo.mms.routes

import io.ktor.server.routing.*
import org.openmbee.flexo.mms.DEFAULT_BRANCH_ID
import org.openmbee.flexo.mms.assertLegalId
import org.openmbee.flexo.mms.routes.ldp.createOrReplaceCollection
import org.openmbee.flexo.mms.server.linkedDataPlatformDirectContainer

private const val COLLECTIONS_PATH = "/orgs/{orgId}/collections"

/**
 * Collection CRUD routing
 */
fun Route.crudCollections() {
    // all collections
    linkedDataPlatformDirectContainer(COLLECTIONS_PATH) {
        beforeEach = {
            parsePathParams {
                org()
            }
        }

//        // state of all collections
//        head {
//            headCollections(true)
//        }
//
//        // read all collections
//        get {
//            getCollections(true)
//        }

        // create a new collection
        post { slug ->
            // assert id is legal
            assertLegalId(slug)

            // set org id on context
            orgId = slug

            // create new org
            createOrReplaceCollection()
        }
    }

    // specific collection
    linkedDataPlatformDirectContainer("$COLLECTIONS_PATH/{collectionId}") {
        beforeEach = {
            parsePathParams {
                org()
                collection()
            }

            // set the default starting branch id
            branchId = DEFAULT_BRANCH_ID
        }

//        // state of a collection
//        head {
//            headCollections()
//        }
//
//        // read an org
//        get {
//            getCollections()
//        }

        // create or replace collection
        put {
            createOrReplaceCollection()
        }

//        // modify existing collection
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
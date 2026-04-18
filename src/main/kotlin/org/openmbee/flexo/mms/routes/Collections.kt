package org.openmbee.flexo.mms.routes

import io.ktor.server.routing.*
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.routes.gsp.readCollectionGraph
import org.openmbee.flexo.mms.routes.ldp.createOrReplaceCollection
import org.openmbee.flexo.mms.routes.ldp.getCollections
import org.openmbee.flexo.mms.routes.ldp.headCollections
import org.openmbee.flexo.mms.server.graphStoreProtocol
import org.openmbee.flexo.mms.server.linkedDataPlatformDirectContainer

const val SPARQL_VAR_NAME_COLLECTION = "_collection"

const val COLLECTIONS_PATH = "/orgs/{orgId}/collections"

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

        // state of all collections
        head {
            headCollections(true)
        }

        // read all collections
        get {
            getCollections(true)
        }

        // create a new collection
        post { slug ->
            // assert id is legal
            assertLegalId(slug)

            // set collection id on context
            collectionId = slug

            // create new collection
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
        }

        // state of a collection
        head {
            headCollections()
        }

        // read a collection
        get {
            getCollections()
        }

        // create or replace collection
        put {
            createOrReplaceCollection()
        }

//        // modify existing collection
//        patch {
////            guardedPatch(
////                updateRequest = it,
////                objectKey = "moc",
////                graph = "m-graph:Cluster",
////                preconditions = UPDATE_COLLECTION_CONDITIONS,
////            )
//        }
    }

    // GSP for collection graph (union of collected ref graphs)
    graphStoreProtocol("$COLLECTIONS_PATH/{collectionId}/graph") {
        beforeEach = {
            parsePathParams {
                org()
                collection()
            }
        }

        // HEAD: check state of collection graph
        head {
            readCollectionGraph()
        }

        // GET: read union graph of all collected refs
        get {
            readCollectionGraph(true)
        }
    }
}

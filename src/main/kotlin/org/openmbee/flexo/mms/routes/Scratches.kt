package org.openmbee.flexo.mms.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.openmbee.flexo.mms.loadGraph
import org.openmbee.flexo.mms.routes.gsp.RefType
import org.openmbee.flexo.mms.routes.gsp.readModel
import org.openmbee.flexo.mms.server.graphStoreProtocol
import org.openmbee.flexo.mms.server.linkedDataPlatformDirectContainer

const val SPARQL_VAR_NAME_SCRATCH = "_scratch"

const val SCRATCHES_PATH = "/orgs/{orgId}/repos/{repoId}/scratches"

/**
 * Scratch CRUD routing
 */
fun Route.crudScratch() {
    // all scratches
    linkedDataPlatformDirectContainer(SCRATCHES_PATH) {
        // state of all scratches
        head {
            headScratches(true)
        }

        // read all scratches
        get {
            getScratches(true)
        }

        // create a new scratch
        post { slug ->
            // set org id on context
            scratchId = slug

            // create new org
            createOrReplaceScratch()
        }
    }

    // GSP specific scratch
    graphStoreProtocol("$SCRATCHES_PATH/{scratchId}/graph") {
        // 5.6 HEAD: check state of scratch graph
        head {
            readModel(RefType.SCRATCH)
        }

        // 5.2 GET: read graph
        get {
            readModel(RefType.SCRATCH)
        }

        // 5.3 PUT: overwrite (load)
        put {
            // load triples directly into mor-graph:Scratch
            loadGraph("${prefixes["mor-graph"]}Scratch.$scratchId")

            // close response
            call.respondText("", status = HttpStatusCode.NoContent)
        }

//        // 5.5 POST: merge
//        post {
//
//        }

//        // 5.7 PATCH: patch
//        patch {
//
//        }

//        // 5.4 DELETE: delete
//        delete {
//
//        }

        otherwiseNotAllowed("scratches")
    }
}

package org.openmbee.flexo.mms.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.openmbee.flexo.mms.loadGraph
import org.openmbee.flexo.mms.routes.gsp.RefType
import org.openmbee.flexo.mms.routes.gsp.readModel
import org.openmbee.flexo.mms.server.graphStoreProtocol


const val SCRATCH_PATH = "/orgs/{orgId}/repos/{repoId}/scratch"

/**
 * Scratch CRUD routing
 */
fun Route.crudScratch() {
    graphStoreProtocol("$SCRATCH_PATH/graph") {
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
            loadGraph("${prefixes["mor-graph"]}Scratch")

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

        otherwiseNotAllowed("scratch")
    }
}

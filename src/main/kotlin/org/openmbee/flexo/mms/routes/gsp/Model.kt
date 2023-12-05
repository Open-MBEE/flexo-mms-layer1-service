package org.openmbee.flexo.mms.routes.endpoints

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import loadModel
import org.openmbee.flexo.mms.MethodNotAllowedException
import org.openmbee.flexo.mms.plugins.graphStore
import org.openmbee.flexo.mms.routes.gsp.readModel


fun Route.modelGsp() {
    graphStore("/orgs/{orgId}/repos/{repoId}/branches/{branchId}/graph") { graphStoreRequest ->
        // depending on request method
        when(graphStoreRequest.method) {
            // read
            HttpMethod.Head, HttpMethod.Get -> {
                readModel(call, graphStoreRequest)
            }

            // overwrite (load)
            HttpMethod.Put -> {
                loadModel(call, graphStoreRequest)
            }

//            // merge
//            HttpMethod.Post -> {
//
//            }

            // not supported
            else -> {
                throw MethodNotAllowedException()
            }
        }

    }
}

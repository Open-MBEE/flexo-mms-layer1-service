package org.openmbee.flexo.mms.routes

import io.ktor.server.routing.*
import loadScratch
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.routes.gsp.RefType
import org.openmbee.flexo.mms.routes.gsp.readModel
import org.openmbee.flexo.mms.routes.ldp.*
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
         beforeEach = {
            parsePathParams {
                org()
                repo()
            }
        }

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

        otherwiseNotAllowed("crud scratches")
    }

    // specific scratch
    linkedDataPlatformDirectContainer("$SCRATCHES_PATH/{scratchId}") {
        beforeEach = {
            parsePathParams {
                org()
                repo()
                scratch()
            }
        }

        // state of a scratch
        head {
            headScratches()
        }

        // read a scratch
        get {
            getScratches()
        }

        // create or replace scratch
        put {
            createOrReplaceScratch()
        }

        // modify existing org
        patch {
            // build conditions
            val localConditions = REPO_CRUD_CONDITIONS.append {
                // scratch must exist
                scratchExists()

                // enforce preconditions if present
                appendPreconditions { values ->
                    """
                        graph mor-graph:Metadata {
                            ${values.reindent(6)}
                        }
                    """
                }

                // require that the user has the ability to update this org on an org-level scope
                permit(Permission.UPDATE_SCRATCH, Scope.SCRATCH)
            }

            // handle all varieties of accepted PATCH request formats
            guardedPatch(
                updateRequest = it,
                objectKey = "mors",
                graph = "mor-graph:Metadata",
                preconditions = localConditions,
            )
        }

        otherwiseNotAllowed("crud scratch")
    }

    // GSP specific scratch
    graphStoreProtocol("$SCRATCHES_PATH/{scratchId}/graph") {
        beforeEach = {
            parsePathParams {
                org()
                repo()
                scratch()
            }
        }

        // 5.6 HEAD: check state of scratch graph
        head {
            readModel(RefType.SCRATCH)
        }

        // 5.2 GET: read graph
        get {
            readModel(RefType.SCRATCH, true)
        }

        // 5.3 PUT: overwrite (load)
        put {
            loadScratch()
        }

//        // 5.5 POST: merge
//        post {
//
//        }

//        // 5.7 PATCH: patch
//        patch {
//
//        }

        // 5.4 DELETE: delete (drop)
        // delete {
        //     deleteScratch()
        // }

        otherwiseNotAllowed("store scratch")
    }
}

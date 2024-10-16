package org.openmbee.flexo.mms.routes

import io.ktor.server.routing.*
import org.openmbee.flexo.mms.LOCK_UPDATE_CONDITIONS
import org.openmbee.flexo.mms.guardedPatch
import org.openmbee.flexo.mms.reindent
import org.openmbee.flexo.mms.routes.ldp.createOrReplaceLock
import org.openmbee.flexo.mms.routes.ldp.deleteLock
import org.openmbee.flexo.mms.routes.ldp.getLocks
import org.openmbee.flexo.mms.routes.ldp.headLocks
import org.openmbee.flexo.mms.server.linkedDataPlatformDirectContainer

private const val LOCKS_PATH = "/orgs/{orgId}/repos/{repoId}/locks"

/**
 * Lock CRUD routing
 */
fun Route.crudLocks() {
    // all locks
    linkedDataPlatformDirectContainer(LOCKS_PATH) {
        beforeEach = {
            parsePathParams {
                org()
                repo()
            }
        }

        // state of a lock
        head {
            headLocks(true)
        }

        // get all locks
        get {
            getLocks(true)
        }

        // create new lock
        post { slug ->
            // set policy id on context
            lockId = slug

            // create new lock
            createOrReplaceLock()
        }

        // method not allowed
        otherwiseNotAllowed("locks")
    }

    // specific lock
    linkedDataPlatformDirectContainer("$LOCKS_PATH/{lockId}") {
        beforeEach = {
            parsePathParams {
                org()
                repo()
                lock()
            }
        }

        // state of a lock
        head {
            headLocks()
        }

        // read a lock
        get {
            getLocks()
        }

        // create or replace lock
        put {
            createOrReplaceLock()
        }

        // update lock metadata
        patch {
            // build conditions
            val localConditions = LOCK_UPDATE_CONDITIONS.append {
                // enforce preconditions if present
                appendPreconditions { values ->
                    """
                        graph mor-graph:Metadata {
                            morl: mms:etag ?__mms_etag .
                            
                            ${values.reindent(6)}
                        }
                    """
                }
            }

            // handle all varieties of accepted PATCH request formats
            guardedPatch(
                updateRequest = it,
                objectKey = "morl",
                graph = "mor-graph:Metadata",
                preconditions = localConditions,
            )
        }

        // delete a lock
        delete {
            deleteLock()
        }

        // method not allowed
        otherwiseNotAllowed("lock")
    }
}

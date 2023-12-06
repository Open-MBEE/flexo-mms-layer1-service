package org.openmbee.flexo.mms.routes

import io.ktor.server.routing.*
import org.openmbee.flexo.mms.assertLegalId
import org.openmbee.flexo.mms.server.linkedDataPlatformDirectContainer
import org.openmbee.flexo.mms.routes.ldp.createOrReplaceLock
import org.openmbee.flexo.mms.routes.ldp.deleteLock
import org.openmbee.flexo.mms.routes.ldp.getLocks

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

        // create new lock
        post { slug ->
            // assert id is legal
            assertLegalId(slug)

            // set policy id on context
            lockId = slug

            // create new lock
            createOrReplaceLock()
        }
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

//        // state of a lock
//        head {
//            headLocks()
//        }

        // read a lock
        get {
            getLocks()
        }

        // create or replace lock
        put {
            // assert id is legal when new resource is being created
            assertLegalId(lockId!!)

            // create/replace lock
            createOrReplaceLock()
        }

        // delete a lock
        delete {
            deleteLock()
        }
    }
}
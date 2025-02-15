package org.openmbee.flexo.mms.routes

import io.ktor.server.routing.*
import org.openmbee.flexo.mms.BRANCH_UPDATE_CONDITIONS
import org.openmbee.flexo.mms.guardedPatch
import org.openmbee.flexo.mms.notImplemented
import org.openmbee.flexo.mms.routes.ldp.createBranch
import org.openmbee.flexo.mms.routes.ldp.getBranches
import org.openmbee.flexo.mms.routes.ldp.headBranches
import org.openmbee.flexo.mms.server.linkedDataPlatformDirectContainer

const val SPARQL_VAR_NAME_BRANCH = "_branch"

private const val BRANCHES_PATH = "/orgs/{orgId}/repos/{repoId}/branches"


/**
 * Branch CRUD routing
 */
fun Route.crudBranches() {
    // all branches
    linkedDataPlatformDirectContainer(BRANCHES_PATH) {
        beforeEach = {
            parsePathParams {
                org()
                repo()
            }
        }

        // state of all branches
        head {
            headBranches()
        }

        // read all branches
        get {
            getBranches()
        }

        // create a new branch
        post { slug ->
            // set branch id on context
            branchId = slug

            // create new branch
            createBranch(usedPost=true)
        }

        // method not allowed
        otherwiseNotAllowed()
    }

    // specific branch
    linkedDataPlatformDirectContainer("$BRANCHES_PATH/{branchId}") {
        beforeEach = {
            parsePathParams {
                org()
                repo()
                branch()
            }
        }

        // state of a branch
        head {
            headBranches(false)
        }

        // read a branch
        get {
            getBranches(true)
        }

        // create branch
        put {
            createBranch()
        }

        // modify existing branch
        patch {
            guardedPatch(
                updateRequest = it,
                objectKey = "morb",
                graph = "mor-graph:Metadata",
                preconditions = BRANCH_UPDATE_CONDITIONS,
            )
        }

        // delete not yet implemented
        delete {
            notImplemented()
        }

        // method not allowed
        otherwiseNotAllowed()
    }
}

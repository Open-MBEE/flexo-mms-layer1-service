package org.openmbee.flexo.mms.routes.ldp

import io.ktor.server.routing.*
import org.openmbee.flexo.mms.assertLegalId
import org.openmbee.flexo.mms.plugins.linkedDataPlatformDirectContainer

const val SPARQL_VAR_NAME_BRANCH = "_branch"

private const val BRANCHES_PATH = "/orgs/{orgId}/repos/{repoId}/branches"


/**
 * Repo CRUD routing
 */
fun Route.CrudBranches() {
    // all repos
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
            // assert id is legal
            assertLegalId(slug)

            // set branch id on context
            branchId = slug

            // create new branch
            createBranch()
        }
    }

    // specific repo
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
            headBranches(branchId)
        }

        // read a branch
        get {
            getBranches(branchId)
        }

        // create branch
        put {
            // assert id is legal when new resource is being created
            assertLegalId(repoId!!)

            // create branch
            createBranch()
        }

//        // modify existing repo
//        patch {
//            guardedPatch(
//                updateRequest = it,
//                objectKey = "mor",
//                graph = "mor-graph:Metadata",
//                preconditions = UPDATE_REPO_CONDITIONS,
//            )
//        }
    }
}
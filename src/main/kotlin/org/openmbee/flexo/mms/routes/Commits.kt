package org.openmbee.flexo.mms.routes

import io.ktor.server.routing.*
import org.openmbee.flexo.mms.COMMIT_UPDATE_CONDITIONS
import org.openmbee.flexo.mms.guardedPatch
import org.openmbee.flexo.mms.routes.ldp.*
import org.openmbee.flexo.mms.server.linkedDataPlatformDirectContainer

const val SPARQL_VAR_NAME_COMMIT = "_commit"

private const val COMMITS_PATH = "/orgs/{orgId}/repos/{repoId}/commits"


/**
 * Branch CRUD routing
 */
fun Route.crudCommits() {
    // all commits
    linkedDataPlatformDirectContainer(COMMITS_PATH) {
        beforeEach = {
            parsePathParams {
                org()
                repo()
            }
        }

        // state of all commits
        head {
            headCommits(true)
        }

        // read all commits
        get {
            getCommits(true)
        }

        // method not allowed
        otherwiseNotAllowed()
    }

    // specific commit
    linkedDataPlatformDirectContainer("$COMMITS_PATH/{commitId}") {
        beforeEach = {
            parsePathParams {
                org()
                repo()
                commit()
            }
        }

        // state of a commit
        head {
            headCommits()
        }

        // read a commit
        get {
            getCommits()
        }

        // modify existing commit
        patch {
            guardedPatch(
                updateRequest = it,
                objectKey = "morc",
                graph = "mor-graph:Metadata",
                preconditions = COMMIT_UPDATE_CONDITIONS,
            )
        }

        // method not allowed
        otherwiseNotAllowed()
    }
}

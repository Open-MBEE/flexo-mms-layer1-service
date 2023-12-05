package org.openmbee.flexo.mms.routes.ldp

import io.ktor.server.routing.*
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.plugins.linkedDataPlatformDirectContainer

const val SPARQL_VAR_NAME_REPO = "_repo"

private const val REPOS_PATH = "/orgs/{orgId}/repos"


/**
 * Repo CRUD routing
 */
fun Route.CrudRepos() {
    // all repos
    linkedDataPlatformDirectContainer(REPOS_PATH) {
        beforeEach = {
            parsePathParams {
                org()
            }
        }

        // state of all repos
        head {
            headRepos(true)
        }

        // read all repos
        get {
            getRepos(true)
        }

        // create a new repo
        post { slug ->
            // assert id is legal
            assertLegalId(slug)

            // set repo id on context
            repoId = slug

            // create new repo
            createOrReplaceRepo()
        }
    }

    // specific repo
    linkedDataPlatformDirectContainer("$REPOS_PATH/{repoId}") {
        beforeEach = {
            parsePathParams {
                org()
                repo()
            }
        }

        // state of a repo
        head {
            headRepos()
        }

        // read a repo
        get {
            getRepos()
        }

        // create or replace repo
        put {
            // assert id is legal when new resource is being created
            assertLegalId(repoId!!)

            // create/replace repo
            createOrReplaceRepo()
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
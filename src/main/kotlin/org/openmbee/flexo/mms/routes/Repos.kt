package org.openmbee.flexo.mms.routes

import io.ktor.server.routing.*
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.server.linkedDataPlatformDirectContainer
import org.openmbee.flexo.mms.routes.ldp.createOrReplaceRepo
import org.openmbee.flexo.mms.routes.ldp.getRepos
import org.openmbee.flexo.mms.routes.ldp.headRepos

const val SPARQL_VAR_NAME_REPO = "_repo"

private const val REPOS_PATH = "/orgs/{orgId}/repos"


/**
 * Repo CRUD routing
 */
fun Route.crudRepos() {
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
            createOrReplaceRepo()
        }

        // modify existing repo
        patch {
            // build conditions
            val localConditions = REPO_UPDATE_CONDITIONS.append {
                // enforce preconditions if present
                appendPreconditions { values ->
                    """
                        graph mor-graph:Metadata {
                            mor: mms:etag ?__mms_etag .
                            ${values.reindent(6)}
                        }
                    """
                }
            }

            // handle all varieties of accepted PATCH request formats
            guardedPatch(
                updateRequest = it,
                objectKey = "mor",
                graph = "mor-graph:Metadata",
                preconditions = localConditions,
            )
        }
    }
}
package org.openmbee.flexo.mms.routes.ldp

import io.ktor.server.routing.*
import org.openmbee.flexo.mms.plugins.linkedDataPlatformDirectContainer

private const val DIFFS_PATH = "/orgs/{orgId}/repos/{repoId}/diffs"

/**
 * Diff CRUD routing
 */
fun Route.CrudDiffs() {
    // all repos
    linkedDataPlatformDirectContainer(DIFFS_PATH) {
        beforeEach = {
            parsePathParams {
                org()
                repo()
            }
        }

        // create a new diff
        post {
            createDiff()
        }
    }
}
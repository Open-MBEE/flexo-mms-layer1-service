package org.openmbee.flexo.mms.routes

import io.ktor.server.routing.*
import org.openmbee.flexo.mms.routes.store.createArtifact
import org.openmbee.flexo.mms.routes.store.getArtifacts
import org.openmbee.flexo.mms.server.storageAbstractionResource


const val SPARQL_VAR_NAME_ARTIFACT = "_artifact"

private const val ARTIFACTS_PATH = "/orgs/{orgId}/repos/{repoId}/artifacts"

/**
 * Artifact store routing
 */
fun Route.storeArtifacts() {
    // all artifacts
    storageAbstractionResource("$ARTIFACTS_PATH/store") {
        beforeEach = {
            parsePathParams {
                org()
                repo()
            }
        }

        // get all artifacts
        get {
//            getArtifacts(true)
        }

        // create new artifact
        post {
            createArtifact()
        }

        // method not allowed
        otherwiseNotAllowed("store artifacts")
    }

    // specific artifact
    storageAbstractionResource("$ARTIFACTS_PATH/store/{artifactId}") {
        beforeEach = {
            parsePathParams {
                org()
                repo()
                artifact()
            }
        }

        // state of an artifact
        head {
//            headArtifact()
        }

        // read an artifact
        get {
            getArtifacts(false)
        }

        // update artifact annotations
        patch {

        }

        // method not allowed
        otherwiseNotAllowed("store artifact")
    }
}

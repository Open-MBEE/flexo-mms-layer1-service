package org.openmbee.flexo.mms.routes

import io.ktor.server.routing.*
import org.openmbee.flexo.mms.ARTIFACT_QUERY_CONDITIONS
import org.openmbee.flexo.mms.assertPreconditions
import org.openmbee.flexo.mms.processAndSubmitUserQuery
import org.openmbee.flexo.mms.routes.store.createArtifact
import org.openmbee.flexo.mms.routes.store.getArtifactsStore
import org.openmbee.flexo.mms.server.sparqlQuery
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
            getArtifactsStore(true)
        }

        // create new artifact
        post {
            createArtifact()
        }

        // method not allowed
        otherwiseNotAllowed("store artifacts")
    }

    // specific artifact
    storageAbstractionResource("$ARTIFACTS_PATH/{artifactId}") {
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
            getArtifactsStore(false)
        }

        // method not allowed
        otherwiseNotAllowed("store artifact")
    }
}


/**
 * Artifact query routing
 */
fun Route.queryArtifacts() {
    // query all artifacts
    sparqlQuery("$ARTIFACTS_PATH/query/{inspect?}") {
        parsePathParams {
            org()
            repo()
            inspect()
        }

        processAndSubmitUserQuery(requestContext, prefixes["mor-artifact"]!!, ARTIFACT_QUERY_CONDITIONS.append {
            assertPreconditions(this)
        })
    }
}

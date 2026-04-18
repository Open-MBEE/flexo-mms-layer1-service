package org.openmbee.flexo.mms.routes.sparql

import io.ktor.server.routing.*
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.routes.COLLECTIONS_PATH
import org.openmbee.flexo.mms.routes.resolveCollectionGraphIris
import org.openmbee.flexo.mms.server.sparqlQuery


/**
 * User submitted SPARQL Query to a specific collection.
 *
 * Resolves all collected refs to their model graph IRIs, then delegates to
 * [processAndSubmitUserQuery] which injects FROM / FROM NAMED clauses into
 * the user's query so it queries across the union of all collected graphs.
 */
fun Route.queryCollection() {
    sparqlQuery("$COLLECTIONS_PATH/{collectionId}/query/{inspect?}") {
        parsePathParams {
            org()
            collection()
            inspect()
        }

        // check conditions (collection exists + READ_COLLECTION permission)
        checkModelQueryConditions(
            targetGraphIri = "urn:mms:collection:query:placeholder",
            conditions = COLLECTION_QUERY_CONDITIONS
        )

        // resolve collected refs to their model graph IRIs and delegate
        val graphIris = resolveCollectionGraphIris()
        processAndSubmitUserQuery(requestContext, graphIris)
    }
}

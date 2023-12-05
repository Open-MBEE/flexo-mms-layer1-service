package org.openmbee.flexo.mms.routes.endpoints

import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.plugins.sparqlQuery


private val SPARQL_SUBSELECT_QUERY = """
    {
        select ?__mms_graph {
            graph m-graph:Cluster {
                moc: mms:ref ?__mms_graph .
            }
        }
    }
"""

/**
 * User submitted SPARQL Query to a specific collection
 */
fun Route.queryCollection() {
    sparqlQuery("/orgs/{orgId}/collections/{collectionId}/query/{inspect?}") {
        parsePathParams {
            org()
            collection()
        }

//            val (rewriter, outputQuery) = sanitizeUserQuery(requestBody)
//
//            // outputQuery.graphURIs.
//
//            // TODO construct query that joins
//            outputQuery.apply {
//                // set default graph
//                graphURIs.clear()
//                // graphURIs.addAll()
//            }.queryPattern.toString()
    }
}

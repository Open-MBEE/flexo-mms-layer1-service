package org.openmbee.mms5.routes.endpoints

import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.openmbee.mms5.*


private val SPARQL_SUBSELECT_QUERY = """
    {
        select ?__mms_graph {
            graph m-graph:Cluster {
                moc: mms:ref ?__mms_graph .
            }
        }
    }
"""

fun Route.queryCollection() {
    post("/orgs/{orgId}/collections/{collectionId}/query/{inspect?}") {
        call.mmsL1(Permission.READ_COLLECTION) {
            pathParams {
                org()
                collection()
            }

            checkPrefixConflicts()

            val (rewriter, outputQuery) = sanitizeUserQuery(requestBody)

            // outputQuery.graphURIs.

            // TODO construct query that joins
            outputQuery.apply {
                // set default graph
                graphURIs.clear()
                // graphURIs.addAll()
            }.queryPattern.toString()
        }

    }
}

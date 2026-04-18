package org.openmbee.flexo.mms.routes.gsp

import io.ktor.http.*
import io.ktor.server.response.*
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.routes.resolveCollectionGraphIris
import org.openmbee.flexo.mms.server.GspLayer1Context
import org.openmbee.flexo.mms.server.GspReadResponse


/**
 * Reads the union graph of all refs collected by a collection.
 *
 * Resolves each `mms:collects` target to its model graph IRI via the shared
 * [resolveCollectionGraphIris] utility, then executes a CONSTRUCT query across
 * all resolved graphs and returns the result as Turtle.
 */
suspend fun GspLayer1Context<GspReadResponse>.readCollectionGraph(allData: Boolean? = false) {
    // check conditions (collection exists + READ_COLLECTION permission)
    checkModelQueryConditions(
        targetGraphIri = "urn:mms:collection:graph:placeholder",
        conditions = COLLECTION_QUERY_CONDITIONS
    )

    // HEAD method
    if (allData != true) {
        call.respond(HttpStatusCode.OK)
        return
    }

    // resolve collected refs to their model graph IRIs
    val graphIris = resolveCollectionGraphIris()

    if (graphIris.isEmpty()) {
        // return empty turtle
        call.respondText("", contentType = RdfContentTypes.Turtle)
        return
    }

    // build CONSTRUCT query with FROM clauses for each graph
    val fromClauses = graphIris.joinToString("\n") { "FROM <$it>" }
    val constructQuery = """
        CONSTRUCT { ?s ?p ?o }
        $fromClauses
        WHERE { ?s ?p ?o }
    """.trimIndent()

    val constructResponse = executeSparqlConstructOrDescribe(constructQuery) {
        acceptReplicaLag = true
    }

    call.respondText(constructResponse, contentType = RdfContentTypes.Turtle)
}

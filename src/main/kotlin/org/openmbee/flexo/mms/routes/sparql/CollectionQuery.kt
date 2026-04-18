package org.openmbee.flexo.mms.routes.sparql

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.apache.jena.query.QueryFactory
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.routes.COLLECTIONS_PATH
import org.openmbee.flexo.mms.routes.resolveCollectionGraphIris
import org.openmbee.flexo.mms.server.sparqlQuery


/**
 * User submitted SPARQL Query to a specific collection.
 *
 * Resolves all collected refs to their model graph IRIs, then injects
 * FROM / FROM NAMED clauses into the user's query so it queries across
 * the union of all collected graphs.
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

        // resolve collected refs to their model graph IRIs
        val graphIris = resolveCollectionGraphIris()

        if (graphIris.isEmpty()) {
            throw Http404Exception("No graphs found for collection")
        }

        // parse user query
        val userQuery = try {
            QueryFactory.create(requestContext.query)
        } catch (parse: Exception) {
            throw QuerySyntaxException(parse)
        }

        // reject any user-specified FROM or FROM NAMED
        if (userQuery.graphURIs.isNotEmpty() || userQuery.namedGraphURIs.isNotEmpty()) {
            throw Http403Exception(this, "FROM target")
        }

        // reject any target graphs from query parameters
        if (requestContext.defaultGraphUris.isNotEmpty() || requestContext.namedGraphUris.isNotEmpty()) {
            throw Http403Exception(this, "graph parameter(s)")
        }

        // inject FROM and FROM NAMED for each resolved graph IRI
        for (graphIri in graphIris) {
            userQuery.graphURIs.add(graphIri)
            userQuery.namedGraphURIs.add(graphIri)
        }

        // serialize the modified query
        var userQueryString = userQuery.serialize()

        // remove BASE from user query
        userQueryString = userQueryString.replace("\\bBASE(\\s*#[^\\n]*)*\\s+<[^>]*>".toRegex(RegexOption.IGNORE_CASE), "")

        // user only wants to inspect the generated query
        if (inspectOnly) {
            call.respondText(userQueryString)
            return@sparqlQuery
        }

        // SELECT or ASK query
        if (userQuery.isSelectType || userQuery.isAskType) {
            val queryResponseText = executeSparqlSelectOrAsk(userQueryString) {
                acceptReplicaLag = true
            }
            call.respondText(queryResponseText, contentType = RdfContentTypes.SparqlResultsJson)
        }
        // CONSTRUCT or DESCRIBE
        else if (userQuery.isConstructType || userQuery.isDescribeType) {
            val queryResponseText = executeSparqlConstructOrDescribe(userQueryString) {
                acceptReplicaLag = true
            }
            call.respondText(queryResponseText, contentType = RdfContentTypes.Turtle)
        }
        // unsupported query type
        else {
            throw Http400Exception("Query operation not supported")
        }
    }
}

package org.openmbee.flexo.mms.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import org.openmbee.flexo.mms.*

/**
 * Encapsulates a canonical Graph Store Protocol request
 */
class GraphStoreRequest(
    call: ApplicationCall,

    // override request content type (default to plain type)
    override val requestContentType: ContentType? = call.defaultRequestContentType(),

    // override response content type (default to null, which means success should respond with 204)
    override val responseContentType: ContentType? = null,

    /**
     * An optional target graph IRI specified in query parameters via `graph`
     */
    val graphIri: String?,

): GenericRequest(call)

// parse the query parameters
fun parseGspParams(call: ApplicationCall): String? {
    val graphIri = call.request.queryParameters["graph"]

    // cannot specify both parameters
    if(graphIri != null && call.request.queryParameters.contains("default")) {
        throw InvalidQueryParameter("cannot specify both `graph` and `default`")
    }

    return graphIri
}

// handling common to GET and HEAD
private fun gspReadLike(call: ApplicationCall): GraphStoreRequest {
    // create data instance
    return GraphStoreRequest(call,
        graphIri = parseGspParams(call),
        responseContentType = call.negotiateRdfResponseContentType(),
    )
}


// handling common to PUT and POST
private fun gspLoadLike(call: ApplicationCall): GraphStoreRequest {
    // create data instance
    return GraphStoreRequest(call,
        graphIri = parseGspParams(call),
        requestContentType = call.expectTriplesRequestContentType()  // TODO: consider accepting multipart/form-data
    )
}



fun Route.graphStore(path: String, body: CustomRouteHandler<GraphStoreRequest>): Route {
    return route(path) {
        // 4.5 HEAD
        head {
            val graphStoreRequest = gspReadLike(call)

            // invoke
            body(graphStoreRequest)
        }

        // 5.2 GET
        get {
            val graphStoreRequest = gspReadLike(call)

            // invoke
            body(graphStoreRequest)
        }

        // 4.3 PUT
        put {
            val graphStoreRequest = gspLoadLike(call)

            // invoke
            body(graphStoreRequest)
        }

        // 5.5 POST
        post {
            val graphStoreRequest = gspLoadLike(call)

            // invoke
            body(graphStoreRequest)
        }

        // 5.7 PATCH
        patch {
            // create data instance
            val graphStoreRequest = GraphStoreRequest(call,
                graphIri = parseGspParams(call),
                requestContentType = call.expectContentTypes(mapOf(
                    RdfContentTypes.SparqlUpdate to RdfContentTypes.SparqlUpdate,
                ))
            )

            // invoke
            body(graphStoreRequest)
        }

        // 5.4 DELETE
        delete {
            // create data instance
            val graphStoreRequest = GraphStoreRequest(call,
                graphIri = parseGspParams(call),
            )

            // invoke
            body(graphStoreRequest)
        }

    }
}
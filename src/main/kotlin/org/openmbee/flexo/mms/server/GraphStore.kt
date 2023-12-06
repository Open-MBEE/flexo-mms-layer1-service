package org.openmbee.flexo.mms.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.openmbee.flexo.mms.*

/**
 * Encapsulates a canonical Graph Store Protocol request
 */
class GspRequest(
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

/**
 * Encapsulates a canonical Graph Store Protocol response
 */
class GspResponse(
    requestContext: GspRequest,
): GenericResponse(requestContext)


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
private fun gspReadLike(call: ApplicationCall): GspRequest {
    // create data instance
    return GspRequest(call,
        graphIri = parseGspParams(call),
        responseContentType = call.negotiateRdfResponseContentType(),
    )
}


// handling common to PUT and POST
private fun gspLoadLike(call: ApplicationCall): GspRequest {
    // create data instance
    return GspRequest(call,
        graphIri = parseGspParams(call),
        requestContentType = call.expectTriplesRequestContentType()  // TODO: consider accepting multipart/form-data
    )
}

suspend fun handleGraphStore(
    body: Layer1Handler<GspRequest, GspResponse>,
    requestContext: GspRequest,
    responseContext: GspResponse= GspResponse(requestContext)
) {
    // create layer1 context
    val layer1 = Layer1Context(requestContext, responseContext)

    // invoke
    body(layer1)
}

fun Route.graphStoreProtocol(path: String, body: Layer1Handler<GspRequest, GspResponse>): Route {
    return route(path) {
        // 4.5 HEAD
        head {
            handleGraphStore(body, gspReadLike(call))
        }

        // 5.2 GET
        get {
            handleGraphStore(body, gspReadLike(call))
        }

        // 4.3 PUT
        put {
            handleGraphStore(body, gspLoadLike(call))
        }

        // 5.5 POST
        post {
            handleGraphStore(body, gspLoadLike(call))
        }

        // 5.7 PATCH
        patch {
            handleGraphStore(body, GspRequest(call,
                graphIri = parseGspParams(call),
                requestContentType = call.expectContentTypes(mapOf(
                    RdfContentTypes.SparqlUpdate to RdfContentTypes.SparqlUpdate,
                ))
            ))
        }

        // 5.4 DELETE
        delete {
            handleGraphStore(body, GspRequest(call,
                graphIri = parseGspParams(call),
            ))
        }

    }
}

typealias GspContext = Layer1Context<GspRequest, GspResponse>
package org.openmbee.flexo.mms.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.openmbee.flexo.mms.*


private val ACCEPTED_RDF_CONTENT_TYPES_POST = listOf(
    RdfContentTypes.Turtle,
    RdfContentTypes.NTriples,
    RdfContentTypes.JsonLd
)

private val ACCEPTED_RDF_CONTENT_TYPES_PATCH = listOf(
    RdfContentTypes.SparqlUpdate,
    RdfContentTypes.RdfPatch,
    *(ACCEPTED_RDF_CONTENT_TYPES_POST.toTypedArray())
)


/**
 * Encapsulates a canonical Graph Store Protocol request
 */
class GspRequest(
    call: ApplicationCall,

    // override request content type (default to plain type)
    override val requestContentType: ContentType? = when(call.request.httpMethod) {
        HttpMethod.Patch -> call.expectContentTypes(mapOf(
            RdfContentTypes.SparqlUpdate to RdfContentTypes.SparqlUpdate,
        ))

        else -> call.defaultRequestContentType()
    },

    // override response content type (default to null, which means success should respond with 204)
    override val responseContentType: ContentType? = when(call.request.httpMethod) {
        HttpMethod.Head, HttpMethod.Get -> call.negotiateRdfResponseContentType()

        // TODO: consider accepting multipart/form-data
        HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch -> call.expectTriplesRequestContentType()

        else -> null
    },

    /**
     * An optional target graph IRI specified in query parameters via `graph`
     */
    val graphIri: String? = call.request.queryParameters["graph"],
): GenericRequest(call) {
    // parse the query parameters
    init {
        // cannot specify both parameters
        if(graphIri != null && call.request.queryParameters.contains("default")) {
            throw InvalidQueryParameter("cannot specify both `graph` and `default`")
        }
    }
}

/**
 * Encapsulates a canonical Graph Store Protocol response
 */
open class GspResponse(requestContext: GenericRequest): GenericResponse(requestContext)

/**
 * Response context for when graph is being read (i.e., HEAD or GET)
 */
open class GspReadResponse(requestContext: GenericRequest): GspResponse(requestContext)

/**
 * Response context for when graph is being mutated to (i.e., POST, PUT or PATCH)
 */
open class GspMutateResponse(requestContext: GenericRequest): GspResponse(requestContext) {
}

/**
 * Response context for HEAD to graph
 */
class GspHeadResponse(requestContext: GenericRequest): GspReadResponse(requestContext)

/**
 * Response context for GET to graph
 */
class GspGetResponse(requestContext: GenericRequest): GspReadResponse(requestContext)

/**
 * Response context for POST to graph
 */
class GspPostResponse(requestContext: GenericRequest): GspMutateResponse(requestContext)

/**
 * Response context for PUT to graph
 */
class GspPutResponse(requestContext: GenericRequest): GspMutateResponse(requestContext)

/**
 * Response context for PATCH to graph
 */
class GspPatchResponse(requestContext: GenericRequest): GspMutateResponse(requestContext)


/**
 * A Graph Store Protocol (GSP) endpoint
 */
fun Route.graphStoreProtocol(
    path: String,
    body: GraphStoreProtocolRoute<GspRequest>.() -> Unit?,
) {
    // scope all contained routes to specified (sub)path
    route(path) {
        // create route builder instance
        val route = GraphStoreProtocolRoute(
            this,
            {
                // create the request context
                GspRequest(this)
            },
            ACCEPTED_RDF_CONTENT_TYPES_POST,
            ACCEPTED_RDF_CONTENT_TYPES_PATCH
        )

        // allow caller to define handlers based on HTTP method
        body(route)
    }
}

/**
 * Route builder for GSP resources
 */
class GraphStoreProtocolRoute<TRequestContext: GenericRequest>(
    route: Route,
    requestContextCreator: RequestContextCreator<TRequestContext>,
    acceptableMediaTypesForPost: List<ContentType>,
    acceptableMediaTypesForPatch: List<ContentType>,
    acceptableMediaTypesForPut: List<ContentType>?=acceptableMediaTypesForPost,
): GenericProtocolRoute<TRequestContext>(
    route,
    requestContextCreator,
    acceptableMediaTypesForPost,
    acceptableMediaTypesForPatch,
    acceptableMediaTypesForPut
) {
    // HEAD
    fun head(body: Layer1Handler<TRequestContext, GspHeadResponse>) {
        super.head(body, { GspHeadResponse(it) }, null)
    }

    // GET
    fun get(body: Layer1Handler<TRequestContext, GspGetResponse>) {
        super.get(body, { GspGetResponse(it) }, null)
    }

    // POST
    fun post(body: suspend Layer1Context<TRequestContext, GspPostResponse>.(slug: String) -> Unit) {
        super.post(body, { GspPostResponse(it) }, null)
    }

    // PUT
    fun put(body: Layer1Handler<TRequestContext, GspPutResponse>) {
        super.put(body, { GspPutResponse(it) }, null)
    }

    // PATCH
    fun patch(body: suspend Layer1Context<TRequestContext, GspPatchResponse>.(updateRequest: SparqlUpdateRequest) -> Unit) {
        super.patch(body, { GspPatchResponse(it) }) {
            // prep update string
            var updateString = ""

            // depending on content-type
            when(val contentType = call.request.contentType().withoutParameters()) {
                // SPARQL Update
                RdfContentTypes.SparqlUpdate -> {
                    updateString = call.receiveText()
                }

                // TODO: transform RDF Patch document into SPARQL Update string
//                // RDF Patch
//                RdfContentTypes.RdfPatch -> {
//
//                }

                // other
                else -> {
                    // Triples document; treat all as INSERT
                    if(contentType in triplesContentTypes) {
                        // prepare model
                        val model = KModel()

                        // attempt to parse triples
                        try {
                            parseRdfByContentType(
                                contentType,
                                call.receiveText(),
                                model,
                                ROOT_CONTEXT + call.request.path()
                            )
                        }
                        catch(parseError: Exception) {
                            throw Http400Exception("Failed to parse triples document: $parseError")
                        }

                        // stringify and inject into INSERT DATA block of SPARQL Update string
                        updateString = """
                            ${model.stringifyPrefixes()}
                            
                            insert data {
                                ${model.stringify()}
                            }
                        """.trimIndent()
                    }
                    // other
                    else {
                        throw UnsupportedMediaType(
                            listOf(
                                RdfContentTypes.SparqlUpdate,
                                *triplesContentTypes,
                            )
                        )
                    }
                }
            }

            // create update request data instance
            SparqlUpdateRequest(call, updateString)
        }
    }
}

/**
 * Layer1Context for any GSP route handler
 */
typealias GspLayer1Context<TResponseContext> = Layer1Context<GspRequest, TResponseContext>

package org.openmbee.flexo.mms.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.openmbee.flexo.mms.*
import java.util.UUID

const val LINKED_DATA_PLATFORM_NS = "http://www.w3.org/ns/ldp#"

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
 * Encapsulates a canonical Linked Data Platform RDF Source (LDP-RS) request
 */
open class LdpRdfSourceRequest(call: ApplicationCall): GenericRequest(call) {
    // negotiate RDF response type
    override val responseContentType = call.negotiateRdfResponseContentType()
}

/**
 * Encapsulates a canonical Linked Data Platform Direct Container (LDP-DC) request
 */
class LdpDirectContainerRequest(call: ApplicationCall): LdpRdfSourceRequest(call)

/**
 * Generic response context for any LDP resource
 */
open class LdpResponse(requestContext: GenericRequest): GenericResponse(requestContext) {
    protected fun stringifyModel(model: KModel): String {
        // determine output format; not supported (should have already rejected)
        val language = contentTypeToLanguage[responseType]
            ?: throw NotAcceptableException(responseType, "Not acceptable RDF format")

        // serialize to destination format
        return model.stringify(language.name, true)
    }

    protected suspend fun respondRdf(turtle: String, statusCode: HttpStatusCode?) {
        // prep response body, default to turtle content
        var response = turtle

        // not turtle, need to convert
        if(responseType != RdfContentTypes.Turtle) {
            // prep model
            val model = KModel()

            // parse turtle content into model
            parseTurtle(turtle, model)

            // serialize to destination format
            response = stringifyModel(model)
        }

        // respond
        call.respondText(response, responseType, statusCode ?: HttpStatusCode.OK)
    }

    protected suspend fun respondRdfModel(model: KModel, statusCode: HttpStatusCode?) {
        call.respondText(stringifyModel(model), responseType, statusCode ?: HttpStatusCode.OK)
    }
}

/**
 * Response context for when a resource is being read (i.e., HEAD or GET)
 */
open class LdpReadResponse(requestContext: GenericRequest): LdpResponse(requestContext)

/**
 * Response context for when a resource is being mutated to (i.e., POST, PUT or PATCH)
 */
open class LdpMutateResponse(requestContext: GenericRequest): LdpResponse(requestContext) {
    /**
     * Sends a success response to an LDP request that asked to create a resource
     */
    suspend fun createdResource(resourceIri: String, responseBodyModel: KModel) {
        // set location header
        call.response.headers.append(HttpHeaders.Location, resourceIri)

        // respond in the request format
        respondRdfModel(responseBodyModel, HttpStatusCode.Created)
    }

    /**
     * Sends a success response to an LDP request that asked to modify a resource
     */
    suspend fun mutatedResource(resourceIri: String, responseBodyModel: KModel) {
        // respond in the request format
        respondRdfModel(responseBodyModel, HttpStatusCode.OK)
    }
}


/**
 * Response context for HEAD to LDP resource
 */
class LdpHeadResponse(requestContext: GenericRequest): LdpReadResponse(requestContext)

/**
 * Response context for GET to LDP resource
 */
class LdpGetResponse(requestContext: GenericRequest): LdpReadResponse(requestContext)

/**
 * Response context for POST to LDP resource
 */
class LdpPostResponse(requestContext: GenericRequest): LdpMutateResponse(requestContext)

/**
 * Response context for PUT to LDP resource
 */
class LdpPutResponse(requestContext: GenericRequest): LdpMutateResponse(requestContext) {
//    suspend fun overwroteResource(resourceIri: String, responseBodyTurtle: String) {
//        // set location header
//        call.response.headers.append("Location", resourceIri)
//
//        // respond in the request
//        call.respondText(responseBodyTurtle, RdfContentTypes.Turtle, HttpStatusCode.Created)
//    }
}

/**
 * Response context for PATCH to LDP resource
 */
class LdpPatchResponse(requestContext: GenericRequest): LdpMutateResponse(requestContext)

/**
 * Response context for DELETE to LDP resource
 */
class LdpDeleteResponse(requestContext: GenericRequest): LdpResponse(requestContext)


/**
 * Any LDP Resource
 */
fun Route.linkedDataPlatformResource(
    path: String,
    subtypes: List<String>?=listOf(),
    body: Route.()->Unit,
) {
    // scope all contained routes to specified (sub)path
    route(path) {
        // 4.2.1.6: all responses must contain the Link header
        intercept(ApplicationCallPipeline.Call) {
            // apply to all response headers
            call.response.headers.apply {
                // prepend "Resource" to list
                val linkTypes = listOf("Resource").plus(subtypes?: listOf())

                // add link resource header
                append("Link", linkTypes.joinToString(", ") { "<${LINKED_DATA_PLATFORM_NS}${it}>; rel=\"type\"" })
            }

            // continue with next middleware
            proceed()
        }

        // allow caller to define pipeline routes
        body()
    }
}

/**
 * Any LDP RDF Source (LDP-RS)
 */
fun Route.linkedDataPlatformRdfSource(
    path: String,
    subtypes: List<String>?=listOf("RDFSource"),
    body: Route.()->Unit,
) {
    linkedDataPlatformResource(path, subtypes) {
        body()
    }
}


/**
 * An LDP Direct Container (LDP-DC)
 */
fun Route.linkedDataPlatformDirectContainer(
    path: String,
    body: LinkedDataPlatformRoute<LdpDirectContainerRequest>.() -> Unit?,
) {
    // direct containers extend LDP-RS
    linkedDataPlatformRdfSource(path, listOf("DirectContainer")) {
        // create route builder instance
        val route = LinkedDataPlatformRoute(
            this,
            {
                // create the request context
                LdpDirectContainerRequest(this)
            },
            ACCEPTED_RDF_CONTENT_TYPES_POST,
            ACCEPTED_RDF_CONTENT_TYPES_PATCH,
        )

        // allow caller to define handlers based on HTTP method
        body(route)
    }
}

/**
 * Route builder for LDP resources
 */
class LinkedDataPlatformRoute<TRequestContext: GenericRequest>(
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
    fun head(body: Layer1Handler<TRequestContext, LdpHeadResponse>) {
        super.head(body, { LdpHeadResponse(it) }, null)
    }

    // GET
    fun get(body: Layer1Handler<TRequestContext, LdpGetResponse>) {
        super.get(body, { LdpGetResponse(it) }, null)
    }

    // POST
    fun post(body: suspend Layer1Context<TRequestContext, LdpPostResponse>.(slug: String) -> Unit) {
        super.post(body, { LdpPostResponse(it) }, null)
    }

    // PUT
    fun put(body: Layer1Handler<TRequestContext, LdpPutResponse>) {
        super.put(body, { LdpPutResponse(it) }, null)
    }

    // PATCH
    fun patch(body: suspend Layer1Context<TRequestContext, LdpPatchResponse>.(updateRequest: SparqlUpdateRequest) -> Unit) {
        super.patch(body, { LdpPatchResponse(it) }) {
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

    // DELETE
    fun delete(body: Layer1Handler<TRequestContext, LdpDeleteResponse>) {
        super.delete(body, { LdpDeleteResponse(it) }, null)
    }
}

/**
 * Layer1Context for any LDP-DC route handler
 */
typealias LdpDcLayer1Context<TResponseContext> = Layer1Context<LdpDirectContainerRequest, TResponseContext>


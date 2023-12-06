package org.openmbee.flexo.mms.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.openmbee.flexo.mms.*

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
        return model.stringify(language.name)
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
 * Response context for when a resource is being written to (i.e., POST, PUT or PATCH)
 */
open class LdpWriteResponse(requestContext: GenericRequest): LdpResponse(requestContext) {
    suspend fun createdResource(resourceIri: String, responseBodyTurtle: String) {
        // set location header
        call.response.headers.append("Location", resourceIri)

        // respond in the request format
        respondRdf(responseBodyTurtle, HttpStatusCode.Created)
    }

    /**
     * Sends a success response to an LDP request that asked to create a resource
     */
    suspend fun createdResource(resourceIri: String, responseBodyModel: KModel) {
        // set location header
        call.response.headers.append("Location", resourceIri)

        // respond in the request format
        respondRdfModel(responseBodyModel, HttpStatusCode.Created)
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
class LdpPostResponse(requestContext: GenericRequest): LdpWriteResponse(requestContext)

/**
 * Response context for PUT to LDP resource
 */
class LdpPutResponse(requestContext: GenericRequest): LdpWriteResponse(requestContext) {
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
class LdpPatchResponse(requestContext: GenericRequest): LdpWriteResponse(requestContext)

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
    val route: Route,
    val requestContextCreator: RequestContextCreator<TRequestContext>,
    private val acceptableMediaTypesForPost: List<ContentType>,
    private val acceptableMediaTypesForPatch: List<ContentType>,
    private val acceptableMediaTypesForPut: List<ContentType>?=acceptableMediaTypesForPost,
) {
    // allowed HTTP methods for a resource matching the given route
    private var allowedMethods = mutableListOf("Options")

    // accepted content types for a POST request to a resource matching the given route
    private var acceptedTypesPost = mutableListOf<ContentType>()

    // accepted content types for a PATCH request to a resource matching the given route
    private var acceptedTypesPatch = mutableListOf<ContentType>()

    // accepted content types for a PUT request to a resource matching the given route
    private var acceptedTypesPut = mutableListOf<ContentType>()


    /**
     * Set a callback to execute before each call handled under given route
     */
    var beforeEach: (suspend Layer1Context<TRequestContext, out GenericResponse>.() -> Unit)? = null

    /**
     * Override the regex used to assert the slug is a legal identifier when POST-ing to create new resource
     */
    var legalSlugRegex: Regex = LEGAL_ID_REGEX


    init {
        // always define OPTIONS
        route.options {
            // handle common by default
            eachCall(call, responseContextCreatorNone())

            // respond with 204 No Content
            call.respond(HttpStatusCode.NoContent)
        }
    }


    // applies custom before each and handles common
    private suspend fun <TResponseContext: GenericResponse> eachCall(
        call: ApplicationCall,
        responseContextCreator: ArgReturn<TRequestContext, TResponseContext>
    ): Layer1Context<TRequestContext, TResponseContext> {
        // create request context
        val requestContext = requestContextCreator(call);

        // create response context
        val responseContext = responseContextCreator(requestContext)

        // create layer1 context
        val layer1 = Layer1Context(requestContext, responseContext)

        // invoke custom beforeEach if defined
        beforeEach?.invoke(layer1)

        // set allowed methods
        call.response.headers.append("Allow", allowedMethods.joinToString { ", " })

        // set accepted RDF formats for POST requests
        if(acceptedTypesPost.isNotEmpty()) {
            call.response.headers.append("Accept-Post", acceptedTypesPost.joinToString(", "))
        }

        // set accepted RDF formats for PATCH requests
        if(acceptedTypesPatch.isNotEmpty()) {
            call.response.headers.append("Accept-Patch", acceptedTypesPatch.joinToString(", "))
        }

        // set accepted RDF formats for PUT requests
        if(acceptedTypesPut.isNotEmpty()) {
            call.response.headers.append("Accept-Put", acceptedTypesPut.joinToString(", "))
        }

        // return layer1 context
        return layer1
    }

    // HEAD
    fun head(body: Layer1Handler<TRequestContext, LdpHeadResponse>) {
        // add to allowed methods
        allowedMethods.add("HEAD")

        // define handler
        route.head {
            // handle common and create layer1 context
            val layer1 = eachCall(call) { LdpHeadResponse(it) }

            // apply caller-defined handling
            body(layer1)
        }
    }

    // GET
    fun get(body: Layer1Handler<TRequestContext, LdpGetResponse>) {
        // add to allowed methods
        allowedMethods.add("GET")

        // define handler
        route.get {
            // handle common and create layer1 context
            val layer1 = eachCall(call) { LdpGetResponse(it) }

            // apply caller-defined handling
            body(layer1)
        }
    }

    // POST
    fun post(body: suspend Layer1Context<TRequestContext, LdpPostResponse>.(slug: String) -> Unit) {
        // add to allowed methods
        allowedMethods.add("POST")

        // add supported RDF content types to accepted POST types
        acceptedTypesPost.addAll(acceptableMediaTypesForPost)

        // define handler
        route.post {
            // handle common and create layer1 context
            val layer1 = eachCall(call) { LdpPostResponse(it) }

            // require slug header
            val slug = call.request.headers["Slug"]
                ?: throw InvalidHeaderValue("missing required `Slug` header which will become new resource's id")

            // assert the slug is a legal identifier
            assertLegalId(slug, legalSlugRegex)

            // apply caller-defined handling
            body(layer1, slug)
        }
    }

    // PUT
    fun put(body: Layer1Handler<TRequestContext, LdpPutResponse>) {
        // add to allowed methods
        allowedMethods.add("PUT")

        // add supported RDF content types to accepted PUT types
        acceptedTypesPut.addAll(acceptableMediaTypesForPut ?: acceptableMediaTypesForPost)

        // define handler
        route.put {
            // handle common and create layer1 context
            val layer1 = eachCall(call) { LdpPutResponse(it) }

            // apply caller-defined handling
            body(layer1)
        }
    }

    // PATCH
    fun patch(body: suspend Layer1Context<TRequestContext, LdpPatchResponse>.(updateRequest: SparqlUpdateRequest) -> Unit) {
        // add to allowed methods
        allowedMethods.add("PATCH")

        // add supported RDF content types to accepted PATCH types
        acceptedTypesPatch.addAll(acceptableMediaTypesForPatch)

        // define handler
        route.patch {
            // handle common and create layer1 context
            val layer1 = eachCall(call) { LdpPatchResponse(it) }

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

                        // stringify and inject into INSERT block of SPARQL Update string
                        updateString = """
                            insert {
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
            val updateRequest = SparqlUpdateRequest(call, updateString)

            // forward update request to body
            body(layer1, updateRequest)
        }
    }

    // DELETE
    fun delete(body: Layer1Handler<TRequestContext, LdpDeleteResponse>) {
        // add to allowed methods
        allowedMethods.add("DELETE")

        // define handler
        route.delete {
            // handle common and create layer1 context
            val layer1 = eachCall(call) { LdpDeleteResponse(it) }

            // apply caller-defined handling
            body(layer1)
        }
    }

    // OPTIONS
    @Deprecated("Don't implement OPTIONS in LDP. Handled by wrapper")
    fun options() {}
}

/**
 * Layer1Context for any LDP-DC route handler
 */
typealias LdpDcLayer1Context<TResponseContext> = Layer1Context<LdpDirectContainerRequest, TResponseContext>


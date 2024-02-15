package org.openmbee.flexo.mms.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import org.openmbee.flexo.mms.LEGAL_ID_REGEX
import org.openmbee.flexo.mms.Layer1Context
import org.openmbee.flexo.mms.assertLegalId
import java.util.*

/**
 * Ushers generic arg and generic return into function
 */
typealias ArgReturn<TArg, TReturn> = (TArg) -> TReturn


/**
 * Convenience function for accessing the plain, default content type given by the request
 */
fun ApplicationCall.defaultRequestContentType(): ContentType {
    return request.contentType().withoutParameters()
}

/**
 * Generically encapsulates a canonical request in some protocol
 */
abstract class GenericRequest(
    val call: ApplicationCall,
) {
    /**
     * Content type of request body (e.g., Turtle, JSON-LD, SPARQL Query, SPARQL Update, RDF Patch, etc.)
     */
    open val requestContentType: ContentType? = call.defaultRequestContentType()

    /**
     * Intended content-type of response. A `null` value indicates that a success response should be 204
     */
    open val responseContentType: ContentType? = null
}

/**
 * Lambda that creates a request context given the application call
 */
typealias RequestContextCreator<TRequestContext> = ApplicationCall.() -> TRequestContext

/**
 * Indicates that no special context is needed for the response
 */
fun <TRequestContext: GenericRequest> responseContextCreatorNone(): ArgReturn<TRequestContext, GenericResponse> {
    return { GenericResponse(it) }
}


/**
 * Generically encapsulates a canonical response in some protocol, which relies on the request context
 */
open class GenericResponse(
    val requestContext: GenericRequest
) {
    /**
     * Convenience getter for application call
     */
    val call
        get() = requestContext.call

    /**
     * Retrieve the negotiated response content type
     */
    val responseType
        get() = requestContext.responseContentType
}

/**
 * Lambda that creates a response context given the request context
 */
typealias ResponseContextCreator<TResponseContext> = (GenericRequest) -> TResponseContext


abstract class GenericProtocolRoute<TRequestContext: GenericRequest>(
    val route: Route,
    val requestContextCreator: RequestContextCreator<TRequestContext>,
    protected val acceptableMediaTypesForPost: List<ContentType>,
    protected val acceptableMediaTypesForPatch: List<ContentType>,
    protected val acceptableMediaTypesForPut: List<ContentType>?=acceptableMediaTypesForPost,
) {
    // allowed HTTP methods for a resource matching the given route
    protected var allowedMethods = mutableListOf("Options")

    // accepted content types for a POST request to a resource matching the given route
    protected var acceptedTypesPost = mutableListOf<ContentType>()

    // accepted content types for a PATCH request to a resource matching the given route
    protected var acceptedTypesPatch = mutableListOf<ContentType>()

    // accepted content types for a PUT request to a resource matching the given route
    protected var acceptedTypesPut = mutableListOf<ContentType>()


    /**
     * Set a callback to execute before each call handled under given route
     */
    var beforeEach: (suspend Layer1Context<TRequestContext, out GenericResponse>.() -> Unit)? = null

    /**
     * Set default regex used to assert the slug is a legal identifier when POST-ing to create new resource
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

    // hook to allow implementor to override during each call
    protected open fun <TResponseContext: GenericResponse> duringEachCall(
        layer1: Layer1Context<TRequestContext, TResponseContext>
    ) {}

    // applies custom before each and handles common
    protected suspend fun <TResponseContext: GenericResponse> eachCall(
        call: ApplicationCall,
        responseContextCreator: ArgReturn<TRequestContext, TResponseContext>
    ): Layer1Context<TRequestContext, TResponseContext> {
        // create request context
        val requestContext = requestContextCreator(call)

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

        // allow implementing class to perform additional logic
        duringEachCall(layer1)

        // return layer1 context
        return layer1
    }


    // hooks for doing something when a route is declared
    protected fun declaredHead() {}
    protected fun declaredGet() {}
    protected fun declaredPost() {}
    protected fun declaredPut() {}
    protected fun declaredPatch() {}
    protected fun declaredDelete() {}


    // HEAD
    fun <TResponseContext: GenericResponse> head(
        body: Layer1Handler<TRequestContext, TResponseContext>,
        responseContextCreator: ResponseContextCreator<TResponseContext>,
        called: ((Layer1Context<TRequestContext, TResponseContext>) -> Unit)?=null
    ) {
        // add to allowed methods
        allowedMethods.add("HEAD")

        // allow implementor to handle when head is declared
        declaredHead()

        // define handler
        route.head {
            // handle common and create layer1 context
            val layer1 = eachCall(call, responseContextCreator)

            // allow implementor to handle when head is called
            called?.invoke(layer1)

            // apply caller-defined handling
            body(layer1)
        }
    }

    // GET
    fun <TResponseContext: GenericResponse> get(
        body: Layer1Handler<TRequestContext, TResponseContext>,
        responseContextCreator: ResponseContextCreator<TResponseContext>,
        called: ((Layer1Context<TRequestContext, TResponseContext>) -> Unit)?=null
    ) {
        // add to allowed methods
        allowedMethods.add("GET")

        // allow implementor to handle when get is declared
        declaredGet()

        // define handler
        route.get {
            // handle common and create layer1 context
            val layer1 = eachCall(call, responseContextCreator)

            // allow implementor to handle when get is called
            called?.invoke(layer1)

            // apply caller-defined handling
            body(layer1)
        }
    }

    // POST
    fun <TResponseContext: GenericResponse> post(
        body: suspend Layer1Context<TRequestContext, TResponseContext>.(slug: String) -> Unit,
        responseContextCreator: ResponseContextCreator<TResponseContext>,
        called: ((Layer1Context<TRequestContext, TResponseContext>, slug: String) -> Unit)?=null
    ) {
        // add to allowed methods
        allowedMethods.add("POST")

        // add supported RDF content types to accepted POST types
        acceptedTypesPost.addAll(acceptableMediaTypesForPost)

        // allow implementor to handle when post is declared
        declaredPost()

        // define handler
        route.post {
            // handle common and create layer1 context
            val layer1 = eachCall(call, responseContextCreator)

            // get slug from header, otherwise generate one from uuid
            val slug = call.request.headers["Slug"]
                ?: UUID.randomUUID().toString()
//                ?: throw InvalidHeaderValue("missing required `Slug` header which will become new resource's id")

            // assert the slug is a legal identifier
            assertLegalId(slug, legalSlugRegex)

            // allow implementor to handle when post is called
            called?.invoke(layer1, slug)

            // apply caller-defined handling
            body(layer1, slug)
        }
    }

    // PUT
    fun <TResponseContext: GenericResponse> put(
        body: Layer1Handler<TRequestContext, TResponseContext>,
        responseContextCreator: ResponseContextCreator<TResponseContext>,
        called: ((Layer1Context<TRequestContext, TResponseContext>) -> Unit)?=null
    ) {
        // add to allowed methods
        allowedMethods.add("PUT")

        // add supported RDF content types to accepted PUT types
        acceptedTypesPut.addAll(acceptableMediaTypesForPut ?: acceptableMediaTypesForPost)

        // allow implementor to handle when put is declared
        declaredPut()

        // define handler
        route.put {
            // handle common and create layer1 context
            val layer1 = eachCall(call, responseContextCreator)

            // allow implementor to handle when put is called
            called?.invoke(layer1)

            // apply caller-defined handling
            body(layer1)
        }
    }

    // PATCH
    fun <TResponseContext: GenericResponse, TUpdateRequest: GenericRequest> patch(
        body: suspend Layer1Context<TRequestContext, TResponseContext>.(updateRequest: TUpdateRequest) -> Unit,
        responseContextCreator: ResponseContextCreator<TResponseContext>,
        called: suspend PipelineContext<Unit, ApplicationCall>.(Layer1Context<TRequestContext, TResponseContext>) -> TUpdateRequest
    ) {
        // add to allowed methods
        allowedMethods.add("PATCH")

        // add supported RDF content types to accepted PATCH types
        acceptedTypesPatch.addAll(acceptableMediaTypesForPatch)

        // allow implementor to handle when patch is declared
        declaredPatch()

        // define handler
        route.patch {
            // handle common and create layer1 context
            val layer1 = eachCall(call, responseContextCreator)

            // allow implementor to define how to construct update request when patch is called
            val updateRequest = called(layer1)

            // forward update request to body
            body(layer1, updateRequest)
        }
    }

    // DELETE
    fun <TResponseContext: GenericResponse> delete(
        body: Layer1Handler<TRequestContext, TResponseContext>,
        responseContextCreator: ResponseContextCreator<TResponseContext>,
        called: ((Layer1Context<TRequestContext, TResponseContext>) -> Unit)?=null
    ) {
        // add to allowed methods
        allowedMethods.add("DELETE")

        // define handler
        route.delete {
            // handle common and create layer1 context
            val layer1 = eachCall(call, responseContextCreator)

            // allow implementor to handle when delete is called
            called?.invoke(layer1)

            // apply caller-defined handling
            body(layer1)
        }
    }

    // OPTIONS
    @Deprecated("Don't implement OPTIONS. Handled by wrapper")
    fun options() {}
}

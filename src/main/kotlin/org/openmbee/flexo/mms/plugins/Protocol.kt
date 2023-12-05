package org.openmbee.flexo.mms.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*

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

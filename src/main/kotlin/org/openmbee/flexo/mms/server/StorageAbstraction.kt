package org.openmbee.flexo.mms.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import org.openmbee.flexo.mms.Layer1Context
import org.openmbee.flexo.mms.MethodNotAllowedException
import org.openmbee.flexo.mms.RdfContentTypes
import org.openmbee.flexo.mms.triplesContentTypes


private val ACCEPTED_RDF_CONTENT_TYPES_PATCH = listOf(
    RdfContentTypes.SparqlUpdate,
    RdfContentTypes.RdfPatch,
    *(triplesContentTypes)
)

/**
 * Encapsulates a canonical StorageAbstraction request
 */
open class StorageAbstractionRequest(call: ApplicationCall): GenericRequest(call)

/**
 * Encapsulates a canonical StorageAbstraction response
 */
open class StorageAbstractionResponse(requestContext: GenericRequest): GenericResponse(requestContext)


/**
 * Response context for when a resource is being read (i.e., HEAD or GET)
 */
open class StorageAbstractionReadResponse(requestContext: GenericRequest): StorageAbstractionResponse(requestContext)

/**
 * Response context for when a resource is being mutated to (i.e., POST, PUT or PATCH)
 */
open class StorageAbstractionMutateResponse(requestContext: GenericRequest): StorageAbstractionResponse(requestContext) {
//    /**
//     * Sends a success response to a StorageAbstraction request that asked to create a resource
//     */
//    suspend fun createdResource(resourceIri: String, responseBodyModel: KModel) {
//        // set location header
//        call.response.headers.append(HttpHeaders.Location, resourceIri)
//
//        // respond in the request format
//        respondRdfModel(responseBodyModel, HttpStatusCode.Created)
//    }
//
//    /**
//     * Sends a success response to a StorageAbstraction request that asked to modify a resource
//     */
//    suspend fun mutatedResource(resourceIri: String, responseBodyModel: KModel) {
//        // respond in the request format
//        respondRdfModel(responseBodyModel, HttpStatusCode.OK)
//    }
}

/**
 * Response context for HEAD to StorageAbstraction resource
 */
class StorageAbstractionHeadResponse(requestContext: GenericRequest): StorageAbstractionReadResponse(requestContext)

/**
 * Response context for GET to StorageAbstraction resource
 */
class StorageAbstractionGetResponse(requestContext: GenericRequest): StorageAbstractionReadResponse(requestContext)

/**
 * Response context for POST to StorageAbstraction resource
 */
class StorageAbstractionPostResponse(requestContext: GenericRequest): StorageAbstractionMutateResponse(requestContext)

/**
 * Response context for PUT to StorageAbstraction resource
 */
class StorageAbstractionPutResponse(requestContext: GenericRequest): StorageAbstractionMutateResponse(requestContext) {
//    suspend fun overwroteResource(resourceIri: String, responseBodyTurtle: String) {
//        // set location header
//        call.response.headers.append("Location", resourceIri)
//
//        // respond in the request
//        call.respondText(responseBodyTurtle, RdfContentTypes.Turtle, HttpStatusCode.Created)
//    }
}

/**
 * Response context for PATCH to StorageAbstraction resource
 */
class StorageAbstractionPatchResponse(requestContext: GenericRequest): StorageAbstractionMutateResponse(requestContext)

/**
 * Response context for DELETE to StorageAbstraction resource
 */
class StorageAbstractionDeleteResponse(requestContext: GenericRequest): StorageAbstractionResponse(requestContext)



/**
 * Any StorageAbstraction Resource
 */
fun Route.storageAbstractionResource(
    path: String,
    body: StorageAbstractionRoute<StorageAbstractionRequest>.()->Unit,
) {
    // scope all contained routes to specified (sub)path
    route(path) {
        // create route builder instance
        val route = StorageAbstractionRoute(
            this,
            {
                // create the request context
                StorageAbstractionRequest(this)
            },
            listOf(ContentType.Any),
            ACCEPTED_RDF_CONTENT_TYPES_PATCH,
        )

        // allow caller to define pipeline routes
        body(route)
    }
}


/**
 * Route builder for StorageAbstraction resources
 */
class StorageAbstractionRoute<TRequestContext: GenericRequest>(
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
    fun head(body: Layer1Handler<TRequestContext, StorageAbstractionHeadResponse>) {
        super.head(body, { StorageAbstractionHeadResponse(it) }, null)
    }

    // GET
    fun get(body: Layer1Handler<TRequestContext, StorageAbstractionGetResponse>) {
        super.get(body, { StorageAbstractionGetResponse(it) }, null)
    }

    // POST
    fun post(body: suspend Layer1Context<TRequestContext, StorageAbstractionPostResponse>.(slug: String) -> Unit) {
        super.post(body, { StorageAbstractionPostResponse(it) }, null)
    }

    // PUT
    fun put(body: Layer1Handler<TRequestContext, StorageAbstractionPutResponse>) {
        super.put(body, { StorageAbstractionPutResponse(it) }, null)
    }

    // PATCH
    fun patch(body: suspend Layer1Context<TRequestContext, StorageAbstractionPatchResponse>.(updateRequest: GenericRequest) -> Unit) {
        super.patch(body, { StorageAbstractionPatchResponse(it) },) { updateRequest ->
            throw MethodNotAllowedException(call.request.httpMethod, updateRequest.requestPath)
        }
    }

    // DELETE
    fun delete(body: Layer1Handler<TRequestContext, LdpDeleteResponse>) {
        super.delete(body, { LdpDeleteResponse(it) }, null)
    }
}

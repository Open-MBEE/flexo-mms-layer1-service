package org.openmbee.flexo.mms.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import org.openmbee.flexo.mms.Layer1Context
import org.openmbee.flexo.mms.RdfContentTypes
import org.openmbee.flexo.mms.UnsupportedMediaType

// canonical sparql update request object
class SparqlUpdateRequest(
    call: ApplicationCall,
    val update: String,
    val defaultGraphUris: Set<String> = setOf(),
    val namedGraphUris: Set<String> = setOf()
): GenericRequest(call)

// SPARQL protocol param ids
private const val updateParamId = "update"
private const val defaultGraphUriParamId = "using-graph-uri"
private const val namedGraphUriParamId = "using-named-graph-uri"

/**
 * Declares a SPARQL Update endpoint
 */
fun Route.sparqlUpdate(path: String, body: Layer1HandlerGeneric<SparqlUpdateRequest>): Route {
    return route(path) {
        // POST
        post {
            // prep update request
            lateinit var updateRequest: SparqlUpdateRequest

            // depending on content-type (without params, e.g., charset)
            when(val contentType = call.request.contentType().withoutParameters()) {
                // 2.2.2 directly
                RdfContentTypes.SparqlUpdate -> {
                    // from query parameters
                    call.request.queryParameters.apply {
                        // usher args
                        val defaultGraphUris = getAll(defaultGraphUriParamId)?.toSet()?: setOf()
                        val namedGraphUris = getAll(namedGraphUriParamId)?.toSet()?: setOf()

                        // receive update string from body
                        val update = call.receiveText()

                        // create data instance
                        updateRequest = SparqlUpdateRequest(call, update, defaultGraphUris, namedGraphUris)
                    }
                }

                // 2.2.1. with URL-encoded parameters
                ContentType.Application.FormUrlEncoded -> {
                    // receive body
                    call.receiveParameters().apply {
                        // usher args
                        val defaultGraphUris = getAll(defaultGraphUriParamId)?.toSet() ?: setOf()
                        val namedGraphUris = getAll(namedGraphUriParamId)?.toSet() ?: setOf()

                        // read update string from body params
                        val update = this[updateParamId] ?: ""

                        // create data instance
                        updateRequest = SparqlUpdateRequest(call, update, defaultGraphUris, namedGraphUris)
                    }
                }

                // other
                else -> {
                    throw UnsupportedMediaType(listOf(
                        RdfContentTypes.SparqlUpdate,
                        ContentType.Application.FormUrlEncoded,
                    ))
                }
            }

            // create layer1 context
            val layer1 = Layer1Context(updateRequest)

            // invoke
            body(layer1)
        }
    }
}

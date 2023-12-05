package org.openmbee.flexo.mms.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import org.openmbee.flexo.mms.Layer1Context
import org.openmbee.flexo.mms.RdfContentTypes
import org.openmbee.flexo.mms.UnsupportedMediaType
import kotlin.text.get

// canonical sparql update request object
data class SparqlUpdateRequest(
    val update: String,
    val defaultGraphUris: Set<String> = setOf(),
    val namedGraphUris: Set<String> = setOf()
)

// SPARQL protocol param ids
private const val updateParamId = "update"
private const val defaultGraphUriParamId = "using-graph-uri"
private const val namedGraphUriParamId = "using-named-graph-uri"

/**
 * Declares a SPARQL Update endpoint
 */
fun Route.sparqlUpdate(path: String, body: Layer1Handler<SparqlUpdateRequest>): Route {
    return route(path) {
        // POST
        post {
            // prep update request
            lateinit var updateRequest: SparqlUpdateRequest

            // depending on content-type (without params, e.g., charset)
            val contentType = call.request.contentType().withoutParameters()
            when(contentType) {
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
                        updateRequest = SparqlUpdateRequest(update, defaultGraphUris, namedGraphUris)
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
                        updateRequest = SparqlUpdateRequest(update, defaultGraphUris, namedGraphUris)
                    }
                }

                // other
                else -> {
                    throw UnsupportedMediaType(contentType.toString())
                }
            }

            // create layer1 context
            val layer1 = Layer1Context(call, updateRequest)

            // invoke
            body(layer1)
        }
    }
}

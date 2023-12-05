package org.openmbee.flexo.mms.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import org.openmbee.flexo.mms.Layer1Context
import org.openmbee.flexo.mms.RdfContentTypes
import org.openmbee.flexo.mms.UnsupportedMediaType

// canonical sparql query request object
class SparqlQueryRequest(
    call: ApplicationCall,
    val query: String,
    val defaultGraphUris: Set<String>,
    val namedGraphUris: Set<String>
): GenericRequest(call)

// SPARQL protocol param ids
private const val queryParamId = "query"
private const val defaultGraphUriParamId = "default-graph-uri"
private const val namedGraphUriParamId = "named-graph-uri"

/**
 * Declares a SPARQL Query endpoint
 */
fun Route.sparqlQuery(path: String, body: Layer1Handler<SparqlQueryRequest>): Route {
    return route(path) {
        // 2.1.1. GET
        get {
            // from query parameters
            call.request.queryParameters.apply {
                // usher args
                val defaultGraphUris = getAll(defaultGraphUriParamId)?.toSet()?: setOf()
                val namedGraphUris = getAll(namedGraphUriParamId)?.toSet()?: setOf()

                // read query string
                val query = this[queryParamId]?: ""

                // create data instance
                val queryRequest = SparqlQueryRequest(call, query, defaultGraphUris, namedGraphUris)

                // create layer1 context
                val layer1 = Layer1Context(call, queryRequest, GenericResponse(queryRequest))

                // invoke
                body(layer1)
            }
        }

        // POST
        post {
            // prep query request
            lateinit var queryRequest: SparqlQueryRequest

            // depending on content-type (without params, e.g., charset)
            val contentType = call.request.contentType().withoutParameters()
            when(contentType) {
                // 2.1.3. directly
                RdfContentTypes.SparqlQuery -> {
                    // from query parameters
                    call.request.queryParameters.apply {
                        // usher args
                        val defaultGraphUris = getAll(defaultGraphUriParamId)?.toSet()?: setOf()
                        val namedGraphUris = getAll(namedGraphUriParamId)?.toSet()?: setOf()

                        // receive query string from body
                        val query = call.receiveText()

                        // create data instance
                        queryRequest = SparqlQueryRequest(query, defaultGraphUris, namedGraphUris)
                    }
                }

                // 2.1.2. with URL-encoded parameters
                ContentType.Application.FormUrlEncoded -> {
                    // receive body
                    call.receiveParameters().apply {
                        // usher args
                        val defaultGraphUris = getAll(defaultGraphUriParamId)?.toSet() ?: setOf()
                        val namedGraphUris = getAll(namedGraphUriParamId)?.toSet() ?: setOf()

                        // read query string from body params
                        val query = this[queryParamId] ?: ""

                        // create data instance
                        queryRequest = SparqlQueryRequest(query, defaultGraphUris, namedGraphUris)
                    }
                }

                // other
                else -> {
                    throw UnsupportedMediaType(contentType.toString())
                }
            }

            // create layer1 context
            val layer1 = Layer1Context(call, queryRequest)

            // invoke
            body(layer1)
        }
    }
}

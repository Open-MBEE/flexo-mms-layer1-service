package org.openmbee.flexo.mms

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.jena.graph.Triple
import org.apache.jena.query.QueryFactory
import org.apache.jena.sparql.core.Var
import org.apache.jena.sparql.engine.binding.BindingBuilder
import org.apache.jena.sparql.syntax.ElementData
import org.apache.jena.sparql.syntax.ElementGroup
import org.apache.jena.sparql.syntax.ElementTriplesBlock
import org.apache.jena.sparql.syntax.ElementUnion
import org.openmbee.flexo.mms.routes.sparql.parseModelStripPrefixes
import org.openmbee.flexo.mms.server.GspRequest
import org.openmbee.flexo.mms.server.SparqlQueryRequest

class QuerySyntaxException(parse: Exception): Exception(parse.stackTraceToString())


suspend fun AnyLayer1Context.checkModelQueryConditions(
    targetGraphIri: String?=null,
    refIri: String?=null,
    conditions: ConditionsGroup
): String {
    // prepare a query to check required conditions and select the appropriate target graph if necessary
    val serviceQuery = """
        select ?targetGraph ?satisfied where {
            ${if(targetGraphIri != null) """
                bind(<$targetGraphIri> as ?targetGraph)
            """.reindent(3) else """
                # select the model graph to query
                graph mor-graph:Metadata {            
                    <$refIri> mms:commit ?commit .
                    ?commit ^mms:commit/mms:snapshot ?snapshot .
                    ?snapshot mms:graph ?targetGraph .
                    
                    # prefer the model snapshot
                    {
                        ?snapshot a mms:Model .
                    }
                    # use staging snapshot if model is not ready
                    union {
                        ?snapshot a mms:Staging .
                        filter not exists {
                            ?snapshot ^mms:snapshot/mms:commit/^mms:commit/mms:snapshot/a mms:Model .
                        }
                    }
                }
            """.reindent(3)}
            
            # check for required conditions
            optional {
                select (1 as ?satisfied) {
                    filter exists {
                        ${conditions.requiredPatterns().joinToString("\n").reindent(6)} 
                    }
                }
            }
        }
    """.reindent(0)

    // attempt service query (let it throw if triplestore returns non-200)
    val serviceQueryResponseText = executeSparqlSelectOrAsk(serviceQuery) {
        acceptReplicaLag = true

        prefixes(prefixes)
    }

    // parse the JSON response
    val bindings = Json.parseToJsonElement(serviceQueryResponseText).jsonObject["results"]!!.jsonObject["bindings"]!!.jsonArray

    // target graph does not exist
    if(0 == bindings.size) {
        throw Http404Exception(call.request.path())
    }

    // required conditions failed
    if(bindings[0].jsonObject["satisfied"] == null) {
        // prep access-control check
        val checkQuery = buildSparqlQuery {
            construct {
                inspections()
            }
            where {
                raw(conditions.unionInspectPatterns())
            }
        }

        // verbose
        log.debug("Submitting post-4xx/5xx access-control check query:\n")

        // execute
        val checkResponseText = executeSparqlConstructOrDescribe(checkQuery) {
            acceptReplicaLag = true

            prefixes(prefixes)
        }

        // parse check response and route to appropriate handler
        parseConstructResponse(checkResponseText) {
            conditions.handle(model, this@checkModelQueryConditions)
        }

        // handler did not terminate connection
        throw ServerBugException("A required condition was not satisfied, but the condition did not handle the exception")
    }

    // return target graph IRI
    return bindings[0].jsonObject["targetGraph"]!!.jsonObject["value"]!!.jsonPrimitive.content
}

/**
 * Checks that all necessary conditions are met (i.e., branch state, access control, etc.) before parsing and transforming
 * a user's SPARQL query by adding patterns that constrain what graph(s) it will select from. It then submits the
 * transformed user query, handling any condition failures, and returns the results to the client.
 */
suspend fun AnyLayer1Context.processAndSubmitUserQuery(queryRequest: SparqlQueryRequest, refIri: String, conditions: ConditionsGroup, addPrefix: Boolean=false, baseIri: String?=null) {
    // for certain sparql, point user query at a predetermined graph
    var targetGraphIri = when(refIri) {
        prefixes["mor"] -> {
            "${prefixes["mor-graph"]}Metadata"
        }
        prefixes["mors"] -> {
            "${prefixes["mor-graph"]}Scratch.$scratchId"
        }
        else -> {
            null
        }
    }

    val targetGraphIriResult = checkModelQueryConditions(targetGraphIri, refIri, conditions)

    // extract the target graph iri from query results
    if(targetGraphIri == null) {
        targetGraphIri = targetGraphIriResult
    }

    // parse user query
    val userQuery = try {
        if(baseIri != null) {
            QueryFactory.create(queryRequest.query, baseIri)
        }
        else {
            QueryFactory.create(queryRequest.query)
        }
    } catch(parse: Exception) {
        throw QuerySyntaxException(parse)
    }

    // transform the user query
    userQuery.apply {
        // no pattern
        if(queryPattern == null) {
            // describe type with explicit iri
            if(isDescribeType && resultURIs.isNotEmpty()) {
                // target node
                val targetNode = resultURIs.first()

                // target var
                val targetVar = Var.alloc("target")

                // set pattern to: { { ?target ?out ?object } union { ?subject ?in ?target } values ?target { <$IRI> } }
                queryPattern = ElementGroup().apply {
                    // union block
                    addElement(ElementUnion().apply {
                        // { ?target ?out ?object }
                        addElement(ElementGroup().apply {
                            addElement(ElementTriplesBlock().apply {
                                addTriple(Triple.create(targetVar, Var.alloc("out"), Var.alloc("object")))
                            })
                        })

                        // { ?subject ?in ?target }
                        addElement(ElementUnion().apply {
                            addElement(ElementTriplesBlock().apply {
                                addTriple(Triple.create(Var.alloc("subject"), Var.alloc("in"), targetVar))
                            })
                        })
                    })

                    // values ?target { <$IRI> }
                    addElement(ElementData(arrayListOf(targetVar), arrayListOf(
                        BindingBuilder.create().add(targetVar, targetNode).build()
                    )))
                }

                // overwrite describe target
                userQuery.resultURIs.apply {
                    clear()
                    add(targetVar)
                }
            }
            // not handled
            else {
                throw Http400Exception("Query type not supported")
            }
        }

        // reject any from or from named
        if(graphURIs.isNotEmpty() || namedGraphURIs.isNotEmpty()) {
            throw Http403Exception(this@processAndSubmitUserQuery, "FROM target")
        }

        // reject any target graphs
        if(queryRequest.defaultGraphUris.isNotEmpty() || queryRequest.namedGraphUris.isNotEmpty()) {
            throw Http403Exception(this@processAndSubmitUserQuery, "graph parameter(s)")
        }

        // set default graph
        graphURIs.add(targetGraphIri)

        // set named graph(s)
        namedGraphURIs.add(targetGraphIri)
    }

    // serialize user query
    var userQueryString = userQuery.serialize()

    // remove BASE from user query
    userQueryString = userQueryString.replace("\\bBASE(\\s*#[^\\n]*)*\\s+<[^>]*>".toRegex(RegexOption.IGNORE_CASE), "")

    // a base IRI was provided; force it back in
    if (baseIri != null) {
       userQueryString = "BASE <${baseIri}>\n" + userQueryString
    }

    // user only wants to inspect the generated query
    if(inspectOnly) {
        call.respondText(userQueryString)
        return
    }

    // SELECT or ASK query
    if(userQuery.isSelectType || userQuery.isAskType) {
        // execute user query
        val queryResponseText = executeSparqlSelectOrAsk(userQueryString) {
            acceptReplicaLag = true

            if(addPrefix) prefixes(prefixes)
        }

        // forward results to client
        call.respondText(queryResponseText, contentType=RdfContentTypes.SparqlResultsJson)
    }
    // CONSTRUCT or DESCRIBE
    else if(userQuery.isConstructType || userQuery.isDescribeType) {
        // execute user query
        val queryResponseText = executeSparqlConstructOrDescribe(userQueryString) {
            acceptReplicaLag = true

            if(addPrefix) prefixes(prefixes)
        }

        // forward results to client
        call.respondText(queryResponseText, contentType=RdfContentTypes.Turtle)
    }
    // unsupported query type
    else {
        throw Http400Exception("Query operation not supported")
    }
}

/**
 * Takes a Graph Store Protocol load request and forwards it to Layer 0
 */
suspend fun Layer1Context<GspRequest, *>.loadGraph(loadGraphUri: String, storeServiceLoadPath: String) {
    // allow client to manually pass in URL to remote file
    var loadUrl: String? = call.request.queryParameters["url"]
    val storeServiceUrl: String? = call.application.storeServiceUrl

    // client did not explicitly provide a URL and the store service is configured
    if (loadUrl == null && storeServiceUrl != null) {
        // submit a POST request to the store service endpoint
        val response: HttpResponse = defaultHttpClient.put("$storeServiceUrl/load/$storeServiceLoadPath") {
            // Pass received authorization to internal service
            headers {
                call.request.headers[HttpHeaders.Authorization]?.let { auth: String ->
                    append(HttpHeaders.Authorization, auth)
                }
            }
            // stream request body from client to store service
            // TODO: Handle exceptions
            setBody(object : OutgoingContent.WriteChannelContent() {
                override val contentType = call.request.contentType()
                override suspend fun writeTo(channel: ByteWriteChannel) {
                    call.request.receiveChannel().copyTo(channel)
                }
            })
        }

        // read response body
        val responseText = response.bodyAsText()

        // non-200
        if (!response.status.isSuccess()) {
            throw Non200Response(responseText, response.status)
        }

        // set load URL
        loadUrl = responseText
    }

    // a load URL has been set
    if (loadUrl != null) {
        // parse types the store service backend accepts
        val acceptTypes = parseAcceptTypes(call.application.storeServiceAccepts)

        // confirm that store service/load supports content-type
        if (!acceptTypes.contains(requestContext.requestContentType)) {
            throw UnsupportedMediaType("Store/LOAD backend does not support ${requestContext.responseContentType}")
        }

        // use SPARQL LOAD
        val loadUpdateString = buildSparqlUpdate {
            raw("""
                load ?_loadUrl into graph ?_loadGraph
            """)
        }

        log("Loading <$loadUrl> into <$loadGraphUri> via: `$loadUpdateString`")

        executeSparqlUpdate(loadUpdateString) {
            prefixes(prefixes)

            iri(
                "_loadUrl" to loadUrl,
                "_loadGraph" to loadGraphUri,
            )
        }

        // exit
        return
    }

    // GSP is configured; use it
    if (call.application.quadStoreGraphStoreProtocolUrl != null) {
        // parse types the gsp backend accepts
        val acceptTypes = parseAcceptTypes(call.application.quadStoreGraphStoreProtocolAccepts)

        // confirm that backend supports content-type
        if (!acceptTypes.contains(requestContext.requestContentType)) {
            throw UnsupportedMediaType("GSP backend does not support loading ${requestContext.responseContentType}")
        }

        // submit a PUT request to the quad-store's GSP endpoint
        val response: HttpResponse = defaultHttpClient.put(call.application.quadStoreGraphStoreProtocolUrl!!) {
            // add the graph query parameter per the GSP specification
            parameter("graph", loadGraphUri)

            // stream request body from client to GSP endpoint
            setBody(object : OutgoingContent.WriteChannelContent() {
                // forward the header for the content type, or default to turtle
                override val contentType = requestContext.requestContentType

                override suspend fun writeTo(channel: ByteWriteChannel) {
                    call.request.receiveChannel().copyTo(channel)
                }
            })
        }

        // read response body
        val responseText = response.bodyAsText()

        // non-200
        if (!response.status.isSuccess()) {
            throw Non200Response(responseText, response.status)
        }
    }
    // fallback to SPARQL UPDATE string
    else {
        // fully load request body
        val body = call.receiveText()
        val model = parseModelStripPrefixes(requestContext.requestContentType!!, body)
        // serialize model into turtle
        val loadUpdateString = buildSparqlUpdate {
            // embed the model in a triples block within the update
            raw("""
                    insert data {
                        # user model
                        graph ?_loadGraph {
                            ${model.stringify()}
                        }
                    }
                """)
        }

        // execute
        executeSparqlUpdate(loadUpdateString) {
            iri(
                "_loadGraph" to loadGraphUri,
            )
        }
    }
}

/**
 * Takes a Graph Store Protocol delete request and forwards it to Layer 0, optionally providing custom WHERE pattern
 */
suspend fun Layer1Context<GspRequest, *>.deleteGraph(deleteGraphUri: String, where: (WhereBuilder.() -> Unit)?) {
    // create update string
    val deleteUpdateString = buildSparqlUpdate {
        delete {
            graph(escapeIri(deleteGraphUri)) {
                raw("?s ?p ?o")
            }
        }
        where {
            where?.let { it() }

            graph(escapeIri(deleteGraphUri)) {
                raw("?s ?p ?o")
            }
        }
    }

    // execute update
    executeSparqlUpdate(deleteUpdateString)
}

package org.openmbee.flexo.mms

import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

import org.apache.jena.graph.Triple
import org.apache.jena.query.QueryFactory
import org.apache.jena.sparql.core.Var
import org.apache.jena.sparql.engine.binding.BindingBuilder
import org.apache.jena.sparql.syntax.*
import org.openmbee.flexo.mms.plugins.SparqlQueryRequest

class QuerySyntaxException(parse: Exception): Exception(parse.stackTraceToString())


/**
 * Checks that all necessary conditions are met (i.e., branch state, access control, etc.) before parsing and transforming
 * a user's SPARQL query by adding patterns that constrain what graph(s) it will select from. It then submits the
 * transformed user query, handling any condition failures, and returns the results to the client.
 */
suspend fun Layer1Context<*, *>.processAndSubmitUserQuery(queryRequest: SparqlQueryRequest, refIri: String, conditions: ConditionsGroup, addPrefix: Boolean=false, baseIri: String?=null) {
    // for certain endpoints, point user query at a predetermined graph
    var targetGraphIri = when(refIri) {
        prefixes["mor"] -> {
            "${prefixes["mor-graph"]}Metadata"
        }
        else -> {
            null
        }
    }

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
                            ?commit ^mms:commit/mms:snapshot/a mms:Model .
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
                auth()
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
            conditions.handle(model, this@processAndSubmitUserQuery)
        }

        // handler did not terminate connection
        throw ServerBugException("A required condition was not satisfied, but the condition did not handle the exception")
    }

    // extract the target graph iri from query results
    if(targetGraphIri == null) {
        targetGraphIri = bindings[0].jsonObject["targetGraph"]!!.jsonObject["value"]!!.jsonPrimitive.content
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
    val userQueryString = userQuery.serialize()

    // user only wants to inspect the generated query
    if(inspectOnly) {
        call.respondText(userQuery.serialize())
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

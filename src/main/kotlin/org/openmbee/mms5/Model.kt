package org.openmbee.mms5

import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

import org.apache.jena.graph.Node
import org.apache.jena.graph.NodeFactory
import org.apache.jena.graph.Triple
import org.apache.jena.query.Query
import org.apache.jena.query.QueryFactory
import org.apache.jena.sparql.core.Var
import org.apache.jena.sparql.engine.binding.BindingBuilder
import org.apache.jena.sparql.path.Path
import org.apache.jena.sparql.path.PathFactory
import org.apache.jena.sparql.syntax.*
import org.apache.jena.sparql.syntax.syntaxtransform.ElementTransformCopyBase
import org.apache.jena.sparql.syntax.syntaxtransform.ElementTransformer
import org.apache.jena.sparql.syntax.syntaxtransform.QueryTransformOps
import org.apache.jena.vocabulary.RDF
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val UTF8Name = StandardCharsets.UTF_8.name()

const val MMS_VARIABLE_PREFIX = "__mms_"

val NEFARIOUS_VARIABLE_REGEX = """[?$]$MMS_VARIABLE_PREFIX""".toRegex()

val MMS_SNAPSHOT_PROPERTY_NODE = MMS.snapshot.asNode()


class QuerySyntaxException(parse: Exception): Exception(parse.stackTraceToString())

class NefariousVariableNameException(name: String): Exception("Nefarious variable name detected: ?$name")



class VariableAccumulator(private val variables: MutableCollection<Var>): PatternVarsVisitor(variables) {
    private fun walk(el: Element) {
        ElementWalker.walk(el, this)
    }

    override fun visit(el: ElementExists) {
        walk(el.element)
    }

    override fun visit(el: ElementNotExists) {
        walk(el.element)
    }

    override fun visit(el: ElementMinus) {
        walk(el.minusElement)
    }

    override fun visit(el: ElementFilter) {
        variables.addAll(el.expr.varsMentioned)
    }

    override fun visit(el: ElementGroup) {
        for(child in el.elements) {
            walk(child)
        }
    }

    override fun visit(el: ElementUnion) {
        for(child in el.elements) {
            walk(child)
        }
    }

    override fun visit(el: ElementOptional) {
        walk(el.optionalElement)
    }

    override fun visit(el: ElementService) {
        // not supported
        throw ServiceNotAllowedException()
    }
}



class GraphNodeRewriter(val prefixes: PrefixMapBuilder, val targetGraphIri: String) {
    private val limiters = hashSetOf<String>()

    val prepend = mutableListOf<Element>()
    val append = mutableListOf<Element>()

    fun rewrite(uri: String): String {
        return "urn:mms:error/access-denied?to=${URLEncoder.encode(uri, UTF8Name)}"
    }

    fun rewrite(node: Node): Node {
        // graph variable
        if(node.isVariable) {
            // first encounter
            if(!limiters.contains(node.name)) {
                // add to set
                limiters.add(node.name)

                // append values block
                val graphVar = Var.alloc(node)
                append.add(ElementData(arrayListOf(graphVar), arrayListOf(
                    BindingBuilder.create().add(graphVar, NodeFactory.createURI(targetGraphIri)).build()
                )))
            }

            // identity
            return node
        }
        // graph IRI
        else if(node.isURI) {
            // rewrite
            return NodeFactory.createURI(this.rewrite(node.uri))
        }

        // unhandled node type
        return NodeFactory.createURI("urn:mms:error/unhandled-node-type")
    }
}

fun MmsL1Context.sanitizeUserQuery(inputQueryString: String, targetGraphIri: String, baseIri: String?=null): Pair<GraphNodeRewriter, Query> {
    // parse query
    val inputQuery = try {
        if(baseIri != null) {
            QueryFactory.create(inputQueryString, baseIri)
        }
        else {
            QueryFactory.create(inputQueryString)
        }
    } catch(parse: Exception) {
        throw QuerySyntaxException(parse)
    }

    if(inputQueryString.contains(NEFARIOUS_VARIABLE_REGEX)) {
        // look in select vars
        for(resultVar in inputQuery.resultVars) {
            if(resultVar.startsWith(MMS_VARIABLE_PREFIX)) {
                throw NefariousVariableNameException(resultVar)
            }
        }

        val variables = mutableListOf<Var>()
        ElementWalker.walk(inputQuery.queryPattern, VariableAccumulator(variables))
        for(variable in variables) {
            if(variable.varName.startsWith(MMS_VARIABLE_PREFIX)) {
                throw NefariousVariableNameException(variable.varName)
            }
        }
    }

    val rewriter = GraphNodeRewriter(prefixes, targetGraphIri)

    return rewriter to QueryTransformOps.transform(inputQuery, object: ElementTransformCopyBase() {
        fun transform(el: Element): Element {
            if(el is ElementSubQuery) {
                val subquery = el.query
                val transformedQuery = subquery.cloneQuery()
                transformedQuery.queryPattern = ElementTransformer.transform(subquery.queryPattern, this)
                return ElementSubQuery(transformedQuery)
            }

            return ElementTransformer.transform(el, this)
        }

        override fun transform(group: ElementGroup, members: MutableList<Element>): Element {
            return ElementGroup().apply {
                elements.addAll(members.map { transform(it) })
            }
        }

        override fun transform(exists: ElementExists, el: Element): Element {
            return ElementExists(transform(el))
        }

        override fun transform(exists: ElementNotExists, el: Element): Element {
            return ElementNotExists(transform(el))
        }

        override fun transform(exists: ElementOptional, el: Element): Element {
            return ElementOptional(transform(el))
        }

        override fun transform(union: ElementUnion, members: MutableList<Element>): Element {
            return ElementUnion().apply {
                elements.addAll(members.map { transform(it) })
            }
        }

        override fun transform(minus: ElementMinus, el: Element): Element {
            return ElementMinus(transform(el))
        }

        override fun transform(subq: ElementSubQuery, query: Query): Element {
            return ElementSubQuery(query.apply { queryPattern = transform(queryPattern) })
        }

        override fun transform(el: ElementService?, service: Node?, elt1: Element?): Element {
            throw ServiceNotAllowedException()
        }

        override fun transform(graph: ElementNamedGraph, gn: Node, subElt: Element): Element {
            return ElementNamedGraph(rewriter.rewrite(gn), subElt)
        }
    }).apply {
        // rewrite from and from named
        graphURIs.replaceAll { rewriter.rewrite(it) }
        namedGraphURIs.replaceAll { rewriter.rewrite(it) }
    }
}

/**
 * Checks that all necessary conditions are met (i.e., branch state, access control, etc.) before parsing and transforming
 * a user's SPARQL query by adding patterns that constrain what graph(s) it will select from. It then submits the
 * transformed user query, handling any condition failures, and returns the results to the client.
 */
suspend fun MmsL1Context.processAndSubmitUserQuery(inputQueryString: String, refIri: String, conditions: ConditionsGroup, addPrefix: Boolean=false, baseIri: String?=null) {
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
        select * where {
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
            conditions.handle(model, mms)
        }

        // handler did not terminate connection
        throw ServerBugException("A required condition was not satisfied, but the condition did not handle the exception")
    }

    // extract the target graph iri from query results
    if(targetGraphIri == null) {
        targetGraphIri = bindings[0].jsonObject["targetGraph"]!!.jsonObject["value"]!!.jsonPrimitive.content
    }

    // sanitize the input query string
    val (rewriter, userQuery) = sanitizeUserQuery(inputQueryString, targetGraphIri, baseIri)

    // transform the user query
    userQuery.apply {
        // unset query result star; jena will convert all top-scoped variables in user's original query to explicit select variables
        if(isQueryResultStar) {
            isQueryResultStar = false
        }

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

        // overwrite the query pattern with a group block
        queryPattern = ElementGroup().apply {
            // anything the rewriter wants to prepend
            rewriter.prepend.forEach { addElement(it) }

            // inline arbitrary user query
            addElement(ElementNamedGraph(NodeFactory.createURI(targetGraphIri), queryPattern))

            // anything the rewriter wants to append
            rewriter.append.forEach { addElement(it) }
        }
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

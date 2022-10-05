package org.openmbee.mms5

import io.ktor.server.response.*
import kotlinx.coroutines.selects.select
import org.apache.jena.graph.Node
import org.apache.jena.graph.NodeFactory
import org.apache.jena.graph.Node_Variable
import org.apache.jena.graph.Triple
import org.apache.jena.iri.IRI
import org.apache.jena.query.Query
import org.apache.jena.query.QueryFactory
import org.apache.jena.sparql.core.PathBlock
import org.apache.jena.sparql.core.TriplePath
import org.apache.jena.sparql.core.Var
import org.apache.jena.sparql.engine.binding.BindingBuilder
import org.apache.jena.sparql.expr.E_Exists
import org.apache.jena.sparql.expr.E_NotExists
import org.apache.jena.sparql.expr.nodevalue.NodeValueNode
import org.apache.jena.sparql.expr.nodevalue.NodeValueString
import org.apache.jena.sparql.path.Path
import org.apache.jena.sparql.path.PathFactory
import org.apache.jena.sparql.syntax.*
import org.apache.jena.sparql.syntax.syntaxtransform.ElementTransformCopyBase
import org.apache.jena.sparql.syntax.syntaxtransform.ElementTransformer
import org.apache.jena.sparql.syntax.syntaxtransform.QueryTransformOps
import org.apache.jena.vocabulary.RDF
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.*

private val UTF8Name = StandardCharsets.UTF_8.name()

const val MMS_VARIABLE_PREFIX = "__mms_"

val NEFARIOUS_VARIABLE_REGEX = """[?$]$MMS_VARIABLE_PREFIX""".toRegex()

val MMS_SNAPSHOT_PROPERTY_NODE = MMS.snapshot.asNode()

val REF_GRAPH_PATH: Path = PathFactory.pathSeq(
    PathFactory.pathLink(MMS_SNAPSHOT_PROPERTY_NODE),
    PathFactory.pathLink(MMS.graph.asNode())
)

// ^mms:snapshot/mms:snapshot/a
val SNAPSHOT_SIBLINGS_PATH: Path = PathFactory.pathSeq(
    PathFactory.pathSeq(
        PathFactory.pathInverse(PathFactory.pathLink(MMS_SNAPSHOT_PROPERTY_NODE)),
        PathFactory.pathLink(MMS_SNAPSHOT_PROPERTY_NODE),
    ),
    PathFactory.pathLink(RDF.type.asNode())
)


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



class GraphNodeRewriter(val prefixes: PrefixMapBuilder) {
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
                    BindingBuilder.create().add(graphVar, NodeFactory.createURI(prefixes["mor-graph"])).build()
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

fun MmsL1Context.sanitizeUserQuery(inputQueryString: String, baseIri: String?=null): Pair<GraphNodeRewriter, Query> {
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

    val rewriter = GraphNodeRewriter(prefixes)

    return rewriter to QueryTransformOps.transform(inputQuery, object: ElementTransformCopyBase() {
        fun transform(el: Element): Element {
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
 * Wraps a user's SPARQL query by adding patterns that constrain what graph(s) it will select from, and perform a check
 * inline that all necessary conditions are met (i.e., branch state, access control, etc.).
 *
 * At an abstract level, the query looks something like this:
 *
 * select * {
 *   {
 *     # use `EXISTS` block to prevent any variables from binding to the top-level scope
 *     filter exists {
 *       # negate the access control check
 *       filter not exists {
 *         graph m-graph:AccessControl.Policies {
 *           # policy check...
 *         }
 *       }
 *
 *       # engine will only evaluate this if the above block matched, meaning that the user *is not* authorized.
 *       # evaluating the expression causes an error to be thrown for the invalid endpoint URI.
 *       service <urn:mms:throw> { }
 *     }
 *   }
 *   # allow the engine to evaluate these two blocks independently
 *   union {
 *     # model graph selection
 *     graph mor-graph:Metadata {
 *       # graph selection...
 *     }
 *
 *     # arbitrary user query
 *     { select ?s ?p ?o { ?s ?p ?o } }
 *   }
 * }
 *
 */
suspend fun MmsL1Context.queryModel(inputQueryString: String, refIri: String, conditions: ConditionsGroup, baseIri: String?=null) {
    val (rewriter, outputQuery) = sanitizeUserQuery(inputQueryString, baseIri)

    // generate a unique substitute variable
    val substituteVar = Var.alloc("${MMS_VARIABLE_PREFIX}${UUID.randomUUID().toString().replace('-', '_')}")

    // prepare a reusable triple pattern placeholder
    val patternPlaceholder = ElementTriplesBlock().apply {
        addTriple(Triple.create(substituteVar, substituteVar, substituteVar))
    }

    // prepare ref node
    val refNode = NodeFactory.createURI(refIri)

    // TODO: add support for ASK, DESCRIBE, and CONSTRUCT queries
    if(!outputQuery.isSelectType) {
        throw ServerBugException("Query type not yet supported")
    }

    // transform the user query
    outputQuery.apply {
        // unset query result star; jena will convert all top-scoped variables in user's original query to explicit select variables
        if(isQueryResultStar) {
            isQueryResultStar = false
        }

        // overwrite the query pattern with a group block
        queryPattern = ElementGroup().apply {
            // create union between auth failure and user query block as a disjunction
            addElement(ElementUnion().apply {
                // the first union member matches iff auth fails
                addElement((ElementGroup().apply {
                    // create service iri binding in same bgp as negation
                    addElement(ElementGroup().apply {
                        // negate authorization query; it must fail in order to produce a binding
                        addElement(ElementFilter(E_NotExists(ElementGroup().apply {
                            addElement(patternPlaceholder)
                        })))

                        // bind(<urn:mms:throw> as ?__mms_service_iri)
                        addElement(ElementBind(
                            Var.alloc( "__mms_service_iri"),
                            NodeValueNode(NodeFactory.createURI("urn:mms:throw"))
                        ))
                    })

                    // throw error using ?__mms_service_iri
                    addElement(ElementService(
                        NodeFactory.createVariable("__mms_service_iri"),
                        ElementTriplesBlock(), false))
                }))

                // add user query block
                addElement(ElementGroup().apply {
                    // authorization query; it must match in order to evaluate user query
                    addElement(ElementFilter(E_Exists(ElementGroup().apply {
                        addElement(patternPlaceholder)
                    })))

                    // prep to set/bind the model graph node
                    lateinit var modelGraphNode: Node

                    // repo query (use metadata graph)
                    when(refIri) {
                        prefixes["mor"] -> {
                            modelGraphNode = NodeFactory.createURI("${prefixes["mor-graph"]}Metadata")
                        }
                        // // apply special optimization for persistent graph URI
                        // prefixes["morb"] -> {
                        //     modelGraphNode = NodeFactory.createURI("${prefixes["mor-graph"]}Latest.${branchId}")
                        // }
                        // need to match model graph dynamically
                        else -> {
                            // model graph selection
                            addElement(ElementNamedGraph(NodeFactory.createURI("${prefixes["mor-graph"]}Metadata"), ElementGroup().apply {
                                // use variable to bind model graph URI
                                modelGraphNode = Var.alloc("${MMS_VARIABLE_PREFIX}modelGraph")

                                // prep intermediate snapshot variable
                                val snapshotVar = Var.alloc("${MMS_VARIABLE_PREFIX}snapshot")

                                // <$REF_IRI> mms:snapshot ?__mms_snapshot .
                                addTriplePattern(Triple.create(refNode, MMS.snapshot.asNode(), snapshotVar))

                                // ?__mms_snapshot mms:graph ?__mms_modelGraphNode .
                                addTriplePattern(Triple.create(snapshotVar, MMS.graph.asNode(), modelGraphNode))

                                // model graph selection
                                addElement(ElementUnion().apply {
                                    // prefer the model snapshot
                                    addElement(ElementTriplesBlock().apply {
                                        // ?__mms_snapshot a mms:Model .
                                        addTriple(Triple.create(snapshotVar, RDF.type.asNode(), MMS.Model.asNode()))
                                    })

                                    // use staging snapshot if model is not ready
                                    addElement(ElementGroup().apply {
                                        // ?__mms_snapshot a mms:Staging .
                                        addTriplePattern(Triple.create(snapshotVar, RDF.type.asNode(), MMS.Staging.asNode()))

                                        // filter not exists { ?__mms_snapshot ^mms:snapshot/mms:snapshot/a mms:Model }
                                        addElement(ElementFilter(E_NotExists(ElementGroup().apply {
                                            addElement(ElementPathBlock().apply {
                                                addTriplePath(TriplePath(snapshotVar, SNAPSHOT_SIBLINGS_PATH, MMS.Model.asNode()))
                                            })
                                        })))
                                    })
                                })
                            }))
                        }
                    }

                    // anything the rewriter wants to prepend
                    rewriter.prepend.forEach { addElement(it) }

                    // inline arbitrary user query
                    addElement(ElementNamedGraph(modelGraphNode, queryPattern))

                    // anything the rewriter wants to append
                    rewriter.append.forEach { addElement(it) }
                })
            })
        }

        // debug select variables
        log.debug("Variables being selected: "+resultVars)
    }


    // serialize the query and replace the substitution pattern with conditions
    val outputQueryString = outputQuery.serialize().replace(
        """[?$]${substituteVar.name}\s+[?$]${substituteVar.name}\s+[?$]${substituteVar.name}\s*\.?""".toRegex(),
        conditions.requiredPatterns().joinToString("\n"))

    if(inspectOnly) {
        call.respondText(outputQueryString)
        return
    }
    else {
        log.info(outputQueryString)
    }

    if(outputQuery.isSelectType || outputQuery.isAskType) {
        val queryResponseText: String
        try {
            queryResponseText = executeSparqlSelectOrAsk(outputQueryString) {}
        }
        catch(executeError: Exception) {
            if(executeError is Non200Response) {
                val statusCode = executeError.status.value

                log.debug("Caught non-200 response from quadstore: ${statusCode} \"\"\"${executeError.body}\"\"\"")

                // 4xx/5xx error
                if(statusCode in 400..599) {
                    // do access control check
                    val checkQuery = buildSparqlQuery {
                        construct {
                            auth()
                        }
                        where {
                            raw(*conditions.requiredPatterns())
                        }
                    }

                    log.debug("Submitting post-4xx/5xx access-control check query:\n${checkQuery}")

                    val checkResponseText = executeSparqlConstructOrDescribe(checkQuery)

                    parseConstructResponse(checkResponseText) {
                        conditions.handle(model, mms)
                    }
                }
            }

            throw executeError
        }

        call.respondText(queryResponseText, contentType=RdfContentTypes.SparqlResultsJson)
    }
    else if(outputQuery.isConstructType || outputQuery.isDescribeType) {
        val queryResponseText = executeSparqlConstructOrDescribe(outputQueryString) {}

        call.respondText(queryResponseText, contentType=RdfContentTypes.Turtle)
    }
    else {
        throw Http400Exception("Query operation not supported")
    }
}
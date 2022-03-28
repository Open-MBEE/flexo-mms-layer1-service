package org.openmbee.mms5

import io.ktor.response.*
import org.apache.jena.graph.Node
import org.apache.jena.graph.NodeFactory
import org.apache.jena.query.Query
import org.apache.jena.query.QueryFactory
import org.apache.jena.sparql.core.PathBlock
import org.apache.jena.sparql.core.TriplePath
import org.apache.jena.sparql.core.Var
import org.apache.jena.sparql.engine.binding.BindingBuilder
import org.apache.jena.sparql.path.Path
import org.apache.jena.sparql.path.PathFactory
import org.apache.jena.sparql.syntax.*
import org.apache.jena.sparql.syntax.syntaxtransform.ElementTransformCopyBase
import org.apache.jena.sparql.syntax.syntaxtransform.ElementTransformer
import org.apache.jena.sparql.syntax.syntaxtransform.QueryTransformOps
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val UTF8Name = StandardCharsets.UTF_8.name()

const val MMS_VARIABLE_PREFIX = "__mms_"

val NEFARIOUS_VARIABLE_REGEX = """[?$]$MMS_VARIABLE_PREFIX""".toRegex()

val REF_GRAPH_PATH: Path = PathFactory.pathSeq(
    PathFactory.pathLink(MMS.snapshot.asNode()),
    PathFactory.pathLink(MMS.graph.asNode())
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
        return "mms://access-denied/?to=${URLEncoder.encode(uri, UTF8Name)}"
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
            return NodeFactory.createURI("no:"+node.uri)
            // rewrite(node.uri)
        }

        // unhandled node type
        return NodeFactory.createURI("mms://error/unhandled-node-type")
    }
}

fun MmsL1Context.sanitizeUserQuery(inputQueryString: String): Pair<GraphNodeRewriter, Query> {
    // parse query
    val inputQuery = try {
        QueryFactory.create(inputQueryString)
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

suspend fun MmsL1Context.queryModel(inputQueryString: String, refIri: String) {
    val (rewriter, outputQuery) = sanitizeUserQuery(inputQueryString)

    outputQuery.apply {
        // // set default graph
        // graphURIs.add(0, "${MMS_VARIABLE_PREFIX}modelGraph")

        // // include repo graph in all named graphs
        // namedGraphURIs.add(0, prefixes["mor-graph"])

        // create new group
        val group = ElementGroup()

        // create metadata graph URI node
        val modelGraphVar = NodeFactory.createVariable("${MMS_VARIABLE_PREFIX}modelGraph")

        // add all prepend root elements
        rewriter.prepend.forEach { group.addElement(it) }

        // add model graph selector
        run {
            val pathBlock = ElementPathBlock(PathBlock().apply {
                add(
                    TriplePath(NodeFactory.createURI(refIri), REF_GRAPH_PATH, modelGraphVar)
                )
            })

            val subQuery = ElementSubQuery(Query().apply {
                setQuerySelectType()
                addResultVar(modelGraphVar)
                queryPattern = ElementNamedGraph(NodeFactory.createURI("${prefixes["mor-graph"]}Metadata"), pathBlock)
                limit = 1
            })

            group.addElement(subQuery)
        }

        // wrap original element in metadata graph
        group.addElement(ElementNamedGraph(modelGraphVar, queryPattern))

        // add all append root elements
        rewriter.append.forEach { group.addElement(it) }

        // set new pattern
        queryPattern = group

        // unset query result star
        if(isQueryResultStar) {
            isQueryResultStar = false
        }

        // resetResultVars()
        log.info("vars: "+resultVars)
    }


    val outputQueryString = outputQuery.serialize()

    if(inspectOnly == true) {
        call.respondText(outputQueryString)
        return
    }
    else {
        log.info(outputQueryString)
    }

    if(outputQuery.isSelectType || outputQuery.isAskType) {
        val queryResponseText = executeSparqlSelectOrAsk(outputQueryString)

        call.respondText(queryResponseText, contentType=RdfContentTypes.SparqlResultsJson)
    }
    else if(outputQuery.isConstructType || outputQuery.isDescribeType) {
        val queryResponseText = executeSparqlConstructOrDescribe(outputQueryString)

        call.respondText(queryResponseText, contentType=RdfContentTypes.Turtle)
    }
    else {
        throw Exception("Query operation not supported")
    }
}
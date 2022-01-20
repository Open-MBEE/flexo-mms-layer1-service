package org.openmbee

import org.apache.jena.graph.Node
import org.apache.jena.graph.NodeFactory
import org.apache.jena.sparql.core.Var
import org.apache.jena.sparql.engine.binding.BindingBuilder
import org.apache.jena.sparql.syntax.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class QuerySyntaxException(parse: Exception): Exception(parse.stackTraceToString())

class NefariousVariableNameException(name: String): Exception("Nefarious variable name detected: ?$name")

private val UTF8Name = StandardCharsets.UTF_8.name()


const val MMS_VARIABLE_PREFIX = "__mms_"
val NEFARIOUS_VARIABLE_REGEX = """[?$]$MMS_VARIABLE_PREFIX""".toRegex()

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
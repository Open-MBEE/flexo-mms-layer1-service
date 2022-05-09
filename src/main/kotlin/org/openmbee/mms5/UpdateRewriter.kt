package org.openmbee.mms5

import io.ktor.http.*
import org.apache.jena.graph.Triple
import org.apache.jena.query.QueryFactory
import org.apache.jena.sparql.core.Quad
import org.apache.jena.sparql.syntax.*


class RequirementNotMetException(info: String): Exception("A required condition was not met. $info")

class QuadsNotAllowedException(graph: String): Exception("Quads not allowed here. Encountered graph `${graph}`") {}

class ServiceNotAllowedException(): Exception("SERVICE blocks not allowed in MMS 5 SPARQL Update") {}

class UpdateOperationNotAllowedException(operation: String): Exception(operation) {}

class UpdateSyntaxException(parse: Exception): Exception(parse.stackTraceToString())

class Non200Response(val body: String, val status: HttpStatusCode): Exception("Quadstore responded with a ${status.value} HTTP status code and the text:\n${body}")


object NoQuadsElementVisitor: ElementVisitor {
    // triples block cannot contain quads
    override fun visit(block: ElementTriplesBlock?) {}

    override fun visit(el: ElementPathBlock?) {}

    override fun visit(el: ElementFilter?) {}

    override fun visit(el: ElementAssign?) {}

    override fun visit(el: ElementBind?) {}

    override fun visit(el: ElementData?) {}

    override fun visit(union: ElementUnion?) {
        assertNoQuads(union?.elements)
    }

    override fun visit(optional: ElementOptional?) {
        optional?.optionalElement?.visit(NoQuadsElementVisitor)
    }

    override fun visit(group: ElementGroup?) {
        assertNoQuads(group?.elements)
    }

    override fun visit(dataset: ElementDataset?) {
        dataset?.dataset?.listGraphNodes()?.forEach { node ->
            throw QuadsNotAllowedException(node.toString())
        }
    }

    override fun visit(el: ElementNamedGraph?) {
        throw QuadsNotAllowedException(el.toString())
    }

    override fun visit(el: ElementExists?) {
        assertNoQuads(el)
    }

    override fun visit(el: ElementNotExists?) {
        assertNoQuads(el)
    }

    override fun visit(el: ElementMinus?) {
        el?.minusElement?.visit(NoQuadsElementVisitor)
    }

    override fun visit(el: ElementService?) {
        throw ServiceNotAllowedException()
    }

    override fun visit(el: ElementSubQuery?) {
        // sub selects do not have a dataset clause, just focus on query pattern
        el?.query?.queryPattern?.visit(NoQuadsElementVisitor)
    }
}

fun assertNoQuads(element: Element1?) {
    element?.element?.visit(NoQuadsElementVisitor)
}

fun assertNoQuads(elements: List<Element>?) {
    elements?.forEach { element ->
        element.visit(NoQuadsElementVisitor)
    }
}


fun asSparqlGroup(vararg elements: Element): String {
    return QueryFactory.make().apply {
        setQueryAskType()

        queryPattern = ElementGroup().apply {
            for(element in elements) {
                addElement(element)
            }
        }
    }.serialize().replace("""^\s*ASK\s+WHERE\s*\{|}$""".toRegex(), "")
        .trim().replace("([^.])$".toRegex(), "$1 .")
}

fun asSparqlGroup(quads: List<Quad>, quadFilter: ((Quad)->Boolean)?=null): String {
    return asSparqlGroup(ElementTriplesBlock().apply {
        for(quad in quads) {
            if(quad.graph != null && !quad.isDefaultGraph) {
                throw QuadsNotAllowedException(quad.graph.toString())
            }

            if(quadFilter != null && !quadFilter(quad)) continue;

            addTriple(Triple(quad.subject, quad.predicate, quad.`object`))
        }
    })
}
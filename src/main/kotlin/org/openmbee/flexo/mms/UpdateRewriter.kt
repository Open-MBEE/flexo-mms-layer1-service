package org.openmbee.flexo.mms

import io.ktor.http.*
import org.apache.jena.graph.Triple
import org.apache.jena.query.QueryFactory
import org.apache.jena.riot.system.PrefixMap
import org.apache.jena.shared.PrefixMapping
import org.apache.jena.shared.impl.PrefixMappingImpl
import org.apache.jena.sparql.core.Quad
import org.apache.jena.sparql.syntax.*
import org.checkerframework.checker.units.qual.Prefix

class RequirementsNotMetException(conditions: List<String>): Http400Exception("The following conditions failed after the transaction attempt:\n"
    +conditions.mapIndexed { i, v -> "${i}. $v" }.joinToString("\n"))

class QuadsNotAllowedException(graph: String): Exception("Quads not allowed here. Encountered graph `${graph}`") {}

class ServiceNotAllowedException(): Exception("SERVICE blocks not allowed in MMS 5 SPARQL Update") {}

class SparqlFeatureNotSupportedException(val info: String): Exception("SPARQL feature not supported: $info") {}

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

    override fun visit(el: ElementLateral?) {
        throw SparqlFeatureNotSupportedException("LATERAL keyword not standardized")
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


fun asSparqlGroup(mapping: PrefixMapping?=null, vararg elements: Element): String {
    return QueryFactory.make().apply {
        setQueryAskType()
        if(mapping != null) prefixMapping = mapping
        queryPattern = ElementGroup().apply {
            for(element in elements) {
                addElement(element)
            }
        }
    }.serialize().replace("""^\s*(PREFIX.*\s*)*\s*ASK\s+WHERE\s*\{|}$""".toRegex(), "")
        .trim().replace("([^.])$".toRegex(), "$1 .")
}

fun asSparqlGroup(
    mapping: PrefixMapping?=null,
    quads: List<Quad>,
    quadFilter: ((Quad)->Boolean)?=null
): String {
    return asSparqlGroup(mapping, ElementTriplesBlock().apply {
        for(quad in quads) {
            if(quad.graph != null && !quad.isDefaultGraph) {
                throw QuadsNotAllowedException(quad.graph.toString())
            }

            if(quadFilter != null && !quadFilter(quad)) continue;

            addTriple(Triple.create(quad.subject, quad.predicate, quad.`object`))
        }
    })
}

fun withPrefixMap(prefixMap: HashMap<String, String>, setup: PrefixMapBuilder.()->Unit): PrefixMapBuilder {
    val prefixes = PrefixMapBuilder()
    prefixes.map = prefixMap
    setup(prefixes)
    return prefixes
}

fun PrefixMapBuilder.asSparqlGroup(vararg elements: Element): String {
    return asSparqlGroup(this.toPrefixMappings(), *elements)
}

fun PrefixMapBuilder.asSparqlGroup(quads: List<Quad>, quadFilter: ((Quad)->Boolean)?=null): String {
    return asSparqlGroup(this.toPrefixMappings(), quads, quadFilter)
}

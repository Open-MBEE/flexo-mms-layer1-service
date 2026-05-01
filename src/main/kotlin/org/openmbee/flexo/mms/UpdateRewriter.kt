package org.openmbee.flexo.mms

import io.ktor.http.*
import org.apache.jena.atlas.io.IndentedWriter
import org.apache.jena.graph.Triple
import org.apache.jena.shared.PrefixMapping
import org.apache.jena.sparql.core.Quad
import org.apache.jena.sparql.serializer.FormatterElement
import org.apache.jena.sparql.serializer.SerializationContext
import org.apache.jena.sparql.syntax.*
import java.io.ByteArrayOutputStream

class RequirementsNotMetException(conditions: List<String>): Http400Exception("The following conditions failed after the transaction attempt:\n"
    +conditions.mapIndexed { i, v -> "${i}. $v" }.joinToString("\n"))

class QuadsNotAllowedException(graph: String): Exception("Quads not allowed here. Encountered graph `${graph}`") {}

class ServiceNotAllowedException(): Exception("SERVICE blocks not allowed in MMS 5 SPARQL Update") {}

class SparqlFeatureNotSupportedException(val info: String): Exception("SPARQL feature not supported: $info") {}

class UpdateOperationNotAllowedException(operation: String): Exception(operation) {}

class UpdateSyntaxException(parse: Exception): Exception(parse.stackTraceToString())

class Non200Response(val body: String, val status: HttpStatusCode): Exception("Quadstore responded with a ${status.value} HTTP status code and the text:\n${body}")

private val MISSING_DOT_REGEX = "([^.])$".toRegex()

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

    override fun visit(el: ElementUnfold?) {
        throw SparqlFeatureNotSupportedException("UNFOLD keyword not supported")
    }

    override fun visit(el: ElementSemiJoin?) {
        throw SparqlFeatureNotSupportedException("SEMI JOIN not supported")
    }

    override fun visit(el: ElementAntiJoin?) {
        throw SparqlFeatureNotSupportedException("ANTI JOIN not supported")
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


fun asSparqlGroup(sCxt: SerializationContext, vararg elements: Element): String {
    val group = ElementGroup().apply {
        for(element in elements) {
            addElement(element)
        }
    }
    val baos = ByteArrayOutputStream()
    val out = IndentedWriter(baos)
    FormatterElement.format(out, sCxt, group)
    out.flush()
    return baos.toString(Charsets.UTF_8).trim().replace(MISSING_DOT_REGEX, "$1 .")
}

fun asSparqlGroup(mapping: PrefixMapping?=null, vararg elements: Element): String {
    val sCxt = if (mapping != null) SerializationContext(mapping) else SerializationContext()
    return asSparqlGroup(sCxt, *elements)
}

fun asSparqlGroup(
    sCxt: SerializationContext,
    quads: List<Quad>,
    quadFilter: ((Quad)->Boolean)?=null
): String {
    return asSparqlGroup(sCxt, ElementTriplesBlock().apply {
        for(quad in quads) {
            if(quad.graph != null && !quad.isDefaultGraph) {
                throw QuadsNotAllowedException(quad.graph.toString())
            }

            if(quadFilter != null && !quadFilter(quad)) continue;

            addTriple(Triple.create(quad.subject, quad.predicate, quad.`object`))
        }
    })
}

fun asSparqlGroup(
    mapping: PrefixMapping?=null,
    quads: List<Quad>,
    quadFilter: ((Quad)->Boolean)?=null
): String {
    val sCxt = if (mapping != null) SerializationContext(mapping) else SerializationContext()
    return asSparqlGroup(sCxt, quads, quadFilter)
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

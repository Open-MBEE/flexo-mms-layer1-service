package org.openmbee.flexo.mms

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import kotlinx.serialization.json.*
import org.apache.commons.io.IOUtils
import org.apache.commons.io.output.ByteArrayOutputStream
import org.apache.jena.datatypes.BaseDatatype
import org.apache.jena.graph.Factory
import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.Property
import org.apache.jena.rdf.model.Resource
import org.apache.jena.rdf.model.impl.ModelCom
import org.apache.jena.riot.Lang
import org.apache.jena.riot.RDFLanguages
import org.apache.jena.riot.RDFParser
import org.apache.jena.riot.system.ErrorHandlerFactory
import org.apache.jena.riot.system.PrefixMapAdapter
import java.nio.charset.StandardCharsets

val COMMA_SEPARATED = """\s*,\s*""".toRegex()

object RdfContentTypes {
    val Turtle = ContentType("text", "turtle")
    val TriG = ContentType("application", "trig")
    val NTriples = ContentType("application", "n-triples")
    val NQuads = ContentType("application", "n-quads")
    val RdfXml = ContentType("application", "rdf+xml")
    val JsonLd = ContentType("application", "ld+json")
    val SparqlQuery = ContentType("application", "sparql-query")
    val SparqlUpdate = ContentType("application", "sparql-update")
    val SparqlResultsJson = ContentType("application", "sparql-results+json")
    val RdfPatch = ContentType("application", "rdf-patch")
//    val Ldpatch = ContentType("application", "ldpatch")

    fun isTriples(contentType: ContentType): Boolean {
        return when(contentType) {
            Turtle, NTriples, RdfXml, JsonLd -> true
            else -> false
        }
    }

    fun isQuads(contentType: ContentType): Boolean {
        return when(contentType) {
             TriG, NQuads -> true
            else -> false
        }
    }
}

val contentTypeToLanguage = mapOf(
    RdfContentTypes.Turtle to RDFLanguages.TURTLE,
    RdfContentTypes.TriG to RDFLanguages.TRIG,
    RdfContentTypes.NTriples to RDFLanguages.NTRIPLES,
    RdfContentTypes.NQuads to RDFLanguages.NQUADS,
    RdfContentTypes.RdfXml to RDFLanguages.RDFXML,
    RdfContentTypes.JsonLd to RDFLanguages.JSONLD,
)

/**
 * Expect the request content type to be in some predefined map, optionally defining a handler for other types
 */
fun ApplicationCall.expectContentTypes(typesMap: Map<ContentType, ContentType>, other: ((contentType: ContentType) -> ContentType)?=null): ContentType {
    // fetch request content type from call
    val contentType = request.contentType().withoutParameters()

    // find mapped content type, otherwise forward to 'other' handle if defined; otherwise throw
    return typesMap[contentType]
        ?: other?.let { it(contentType) }
        ?: throw UnsupportedMediaType(typesMap.keys.joinToString(","))
}

/**
 * Expect the request content type to be triples, optionally defining a handler for other types
 */
fun ApplicationCall.expectTriplesRequestContentType(other: ((contentType: ContentType) -> ContentType)?=null): ContentType {
    return expectContentTypes(mapOf(
        RdfContentTypes.NTriples to RdfContentTypes.Turtle,
        RdfContentTypes.Turtle to RdfContentTypes.Turtle,
        RdfContentTypes.JsonLd to RdfContentTypes.JsonLd,
        RdfContentTypes.RdfXml to RdfContentTypes.RdfXml,
    )) { contentType ->
        other?.let { it(contentType) }
            ?: throw InvalidTriplesDocumentTypeException(contentType.toString())
    }
}

/**
 * When the response content type is expected to be RDF, negotiate the exact type depending on the request's Accept header
 */
fun ApplicationCall.negotiateRdfResponseContentType(): ContentType {
    // prep destination type
    var destinationType: ContentType? = null

    // parse accept types in descending quality order
    val acceptTypes = parseAcceptTypes(request.acceptItems())

    // depending on accept type
    when {
        // anything that can be represented using turtle
        acceptTypes.isEmpty()
        || acceptTypes.contains(ContentType.Any)
        || acceptTypes.contains(ContentType.Text.Any)
        || acceptTypes.contains(ContentType.Application.Any)
        || acceptTypes.contains(RdfContentTypes.Turtle)
        || acceptTypes.contains(RdfContentTypes.TriG)
        -> {
            destinationType = RdfContentTypes.Turtle
        }

        // n-quads subset
        acceptTypes.contains(RdfContentTypes.NQuads)
        || acceptTypes.contains(RdfContentTypes.NTriples) -> {
            destinationType = RdfContentTypes.NTriples
        }

        // json-ld
        acceptTypes.contains(RdfContentTypes.JsonLd) -> {
            destinationType = RdfContentTypes.JsonLd
        }

        // rdf-xml
        acceptTypes.contains(RdfContentTypes.RdfXml) -> {
            destinationType = RdfContentTypes.RdfXml
        }

        // failed to negotiate type
        else -> {
            throw NotAcceptableException(
                acceptTypes.joinToString(", ") { it.toString() },
                "*/*, text/turtle, application/n-triples, application/ld+json, application/rdf+xml"
            )
        }
    }

    return destinationType
}

class KModel(val prefixes: PrefixMapBuilder=PrefixMapBuilder(), setup: (KModel.() -> Unit)?=null): ModelCom(Factory.createGraphMem()) {
    init {
        this.setNsPrefixes(prefixes.map)
        if(null != setup) setup()
    }

    fun addNodes(subject: Resource, vararg pairs: Pair<Property, String>): KModel {
        for((property, node) in pairs) {
            this.add(subject, property, this.createResource(node))
        }

        return this
    }

    fun addLiterals(subject: Resource, vararg pairs: Pair<Property, Pair<String, String?>>): KModel {
        for((property, pair) in pairs) {
            val (content, langOrDatatype) = pair

            if(langOrDatatype.isNullOrEmpty()) {
                this.add(subject, property, content)
            }
            else if('@' == langOrDatatype[0]) {
                this.add(subject, property, content, langOrDatatype.substring(1))
            }
            else {
                this.add(subject, property, content, BaseDatatype(langOrDatatype))
            }
        }

        return this
    }

    fun stringify(lang: String="TURTLE", emitPrefixes: Boolean=false): String {
        val outputStream = ByteArrayOutputStream()
        this.write(outputStream, lang)
        return outputStream.toString(StandardCharsets.UTF_8.name()).run {
            if(!emitPrefixes) {
                return replaceFirst("^\\s*@?prefix\\s+.*\\r?\\n\\r?\\n\\s*".toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "\t\t")
                    .trimIndent()
            }

            this
        }
    }
}


fun parseRdf(language: Lang, body: String, model: Model, baseIri: String?=null, prefixes: PrefixMapBuilder?=null) {
    // parse input document
    RDFParser.create().apply {
        prefixes(PrefixMapAdapter(prefixes?.toPrefixMappings()))
        lang(language)
        errorHandler(ErrorHandlerFactory.errorHandlerWarn)
        if(baseIri != null) base(baseIri)
        source(IOUtils.toInputStream(body, StandardCharsets.UTF_8))
        parse(model)
    }
}

fun parseTurtle(body: String, model: Model, baseIri: String?=null, prefixes: PrefixMapBuilder?=null) {
    parseRdf(RDFLanguages.TURTLE, body, model, baseIri, prefixes)
}


fun parseRdfByContentType(contentType: ContentType, body: String, model: Model, baseIri: String?=null, prefixes: PrefixMapBuilder?=null) {
    val language = contentTypeToLanguage[contentType]
    if(language == null) {
        throw Exception("No RDF content parser available for $contentType")
    }

    return parseRdf(language, body, model, baseIri, prefixes)
}

fun parseAcceptTypes(types: String?): HashSet<ContentType> {
    return types?.trim()?.split(COMMA_SEPARATED)
        ?.map { ContentType.parse(it) }
        ?.toHashSet() ?: hashSetOf()
}

fun parseAcceptTypes(types: List<HeaderValue>): List<ContentType> {
    // parsing .value drops the parameters
    return types.map { ContentType.parse(it.value) }
}

fun parseSparqlResultsJson(selectResponseText: String): List<JsonObject> {
    return Json.parseToJsonElement(selectResponseText).jsonObject["results"]!!.jsonObject["bindings"]!!.jsonArray.map { it.jsonObject }
}

fun AnyLayer1Context.parseConstructResponse(responseText: String, setup: RdfModeler.()->Unit): KModel {
    return RdfModeler(this, prefixes["m"]!!, responseText).apply(setup).model
}

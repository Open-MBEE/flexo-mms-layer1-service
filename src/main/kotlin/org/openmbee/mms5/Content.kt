package org.openmbee.mms5

import io.ktor.http.*
import org.apache.commons.io.IOUtils
import org.apache.commons.io.output.ByteArrayOutputStream
import org.apache.jena.datatypes.BaseDatatype
import org.apache.jena.graph.Factory
import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.Property
import org.apache.jena.rdf.model.Resource
import org.apache.jena.rdf.model.impl.ModelCom
import org.apache.jena.riot.RDFLanguages
import org.apache.jena.riot.RDFParser
import org.apache.jena.riot.system.ErrorHandlerFactory
import java.nio.charset.StandardCharsets


object RdfContentTypes {
    val Turtle = ContentType("text", "turtle")
    val RdfXml = ContentType("application", "rdf+xml")
    val JsonLd = ContentType("application", "ld+json")
    val SparqlQuery = ContentType("application", "sparql-query")
    val SparqlUpdate = ContentType("application", "sparql-update")
    val SparqlResultsJson = ContentType("application", "sparql-results+json")

    fun isTriples(contentType: ContentType): Boolean {
        return when(contentType) {
            Turtle, RdfXml, JsonLd -> true
            else -> false
        }
    }

    fun fromString(type: String): ContentType {
        val sanitize = ContentType.parse(type).withoutParameters()
        val mime = "${sanitize.contentType}/${sanitize.contentSubtype}"

        return when(mime) {
            "text/turtle" -> Turtle
            "application/rdf+xml" -> RdfXml
            "application/ld+json" -> JsonLd
            "application/sparql-query" -> SparqlQuery
            "application/sparql-update" -> SparqlUpdate
            "application/sparql-results+json" -> SparqlResultsJson
            else -> sanitize
        }
    }
}

class KModel(val prefixes: PrefixMapBuilder, setup: (KModel.() -> Unit)?=null): ModelCom(Factory.createGraphMem()) {
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

fun parseTurtle(body: String, model: Model, baseIri: String?=null, prefixes: PrefixMapBuilder?=null) {
    val documentString = "${prefixes?: ""}\n$body"

    // parse input document
    RDFParser.create().apply {
        source(IOUtils.toInputStream(documentString, StandardCharsets.UTF_8))
        lang(RDFLanguages.TURTLE)
        errorHandler(ErrorHandlerFactory.errorHandlerWarn)
        if(baseIri != null) base(baseIri)
        parse(model)
    }
}


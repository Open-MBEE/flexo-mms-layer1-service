package org.openmbee

import org.apache.jena.datatypes.RDFDatatype
import org.apache.jena.query.ParameterizedSparqlString
import org.apache.jena.shared.PrefixMapping


class Parameterizer(sparql: String, prefixes: PrefixMapping?=null) {
    private val pss = if(null != prefixes) ParameterizedSparqlString(sparql, prefixes) else ParameterizedSparqlString(sparql)

    fun prefixes(prefixes: PrefixMapBuilder): Parameterizer {
        pss.setNsPrefixes(prefixes.map)

        return this
    }

    fun iri(vararg map: Pair<String, String>): Parameterizer {
        for((key, value) in map) {
            pss.setIri(key, value)
        }

        return this
    }

    fun literal(vararg map: Pair<String, String>): Parameterizer {
        for((key, value) in map) {
            pss.setLiteral(key, value)
        }

        return this
    }

    fun datatyped(vararg map: Pair<String, Pair<String, RDFDatatype>>): Parameterizer {
        for((key, pair) in map) {
            pss.setLiteral(key, pair.first, pair.second)
        }

        return this
    }

    override fun toString(): String {
        return pss.toString()
    }
}

fun parameterizedSparql(sparql: String, setup: Parameterizer.() -> Parameterizer): String {
    return setup(Parameterizer(sparql)).toString()
}

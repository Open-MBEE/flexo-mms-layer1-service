package org.openmbee.flexo.mms

import org.apache.jena.datatypes.RDFDatatype
import org.apache.jena.query.ParameterizedSparqlString


class SparqlParameterizer(sparql: String, prefixes: PrefixMapBuilder?=null) {
    private val pss = if(null != prefixes) ParameterizedSparqlString(sparql, prefixes.toPrefixMappings()) else ParameterizedSparqlString(sparql)

    var acceptReplicaLag = false

    fun prefixes(prefixes: PrefixMapBuilder): SparqlParameterizer {
        pss.setNsPrefixes(prefixes.map)

        return this
    }

    fun iri(vararg map: Pair<String, String>): SparqlParameterizer {
        for((key, value) in map) {
            pss.setIri(key, value)
        }

        return this
    }

    fun literal(vararg map: Pair<String, String>): SparqlParameterizer {
        for((key, value) in map) {
            pss.setLiteral(key, value)
        }

        return this
    }

    fun datatyped(vararg map: Pair<String, Pair<String, RDFDatatype>>): SparqlParameterizer {
        for((key, pair) in map) {
            pss.setLiteral(key, pair.first, pair.second)
        }

        return this
    }

    override fun toString(): String {
        return pss.toString()
    }
}

fun parameterizedSparql(sparql: String, setup: SparqlParameterizer.() -> SparqlParameterizer): String {
    return setup(SparqlParameterizer(sparql)).toString()
}

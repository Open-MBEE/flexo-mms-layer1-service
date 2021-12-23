package org.openmbee

import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.*
import org.apache.jena.shared.PrefixMapping


val ROOT_CONTEXT = (System.getenv("MMS5_ROOT_CONTEXT")?: "https://mms.openmbee.org/demo").replace("/+$".toRegex(), "")
val STORE_QUERY_URI = System.getenv("MMS5_STORE_QUERY")?: "http://localhost:8081/bigdata/namespace/kb/sparql"
val STORE_UPDATE_URI = System.getenv("MMS5_STORE_UPDATE")?: "http://localhost:8081/bigdata/namespace/kb/sparql"
val SERVICE_ID = System.getenv("MMS5_SERVICE_ID")?: "local-dev-0"

class PrefixMapBuilder(other: PrefixMapBuilder?=null, setup: (PrefixMapBuilder.() -> PrefixMapBuilder)?=null) {
    var map = HashMap<String, String>()

    init {
        if(null != other) map.putAll(other.map)
        if(null != setup) setup(this)
    }

    fun add(vararg adds: Pair<String, String>): PrefixMapBuilder {
        map.putAll(adds)
        return this
    }

    operator fun get(prefix: String): String? {
        return map[prefix]
    }

    override fun toString(): String {
        return map.entries.fold("") {
            out, (key, value) -> out + "prefix $key: <$value>\n"
        }
    }

    fun toPrefixMappings(): PrefixMapping {
        return PrefixMapping.Factory.create().run {
            setNsPrefixes(map)
        }
    }
}

val SPARQL_PREFIXES = PrefixMapBuilder() {
    add(
        "rdf" to "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
        "rdfs" to "http://www.w3.org/2000/01/rdf-schema#",
        "owl" to "http://www.w3.org/2002/07/owl#",
        "xsd" to "http://www.w3.org/2001/XMLSchema#",
        "dct" to "http://purl.org/dc/terms/",
    )

    with("https://openmbee.org/rdf/mms") {
        add(
            "mms" to "$this/ontology/",
            "mms-object" to "$this/objects/",
            "mms-datatype" to "$this/datatypes/",
        )
    }

    with(ROOT_CONTEXT) {
        add(
            "m" to "$this/",
            "m-object" to "$this/objects/",
            "m-graph" to "$this/graphs/",
            "m-org" to "$this/orgs/",
            "m-project" to "$this/projects/",
            "m-user" to "$this/users/",
            "m-group" to "$this/groups/",
            "m-policy" to "$this/policies/",
        )
    }
}

fun prefixesFor(
    userId: String?=null,
    orgId: String?=null,
    projectId: String?=null,
    branchId: String?=null,
    commitId: String?=null,
    transactionId: String?=null,
    source: PrefixMapBuilder?=SPARQL_PREFIXES
): PrefixMapBuilder {
    return PrefixMapBuilder(source) {
        if(null != userId) {
            with("$ROOT_CONTEXT/users/$userId") {
                add(
                    "mu" to this,
                )
            }
        }

        if(null != orgId) {
            with("$ROOT_CONTEXT/orgs/$orgId") {
                add(
                    "mo" to this,
                )
            }
        }

        if(null != transactionId) {
            with("$ROOT_CONTEXT/transactions/$transactionId") {
                add(
                    "mt" to this,
                )
            }
        }

        if(null != projectId) {
            with("$ROOT_CONTEXT/projects/$projectId") {
                add(
                    "mp" to this,
                    "mp-branch" to "$this/branches/",
                    "mp-lock" to "$this/locks/",
                    "mp-graph" to "$this/graphs/",
                    "mp-commit" to "$this/commits/",
                )

                if(null != branchId) {
                    with("$this/branches/$branchId") {
                        add(
                            "mpb" to this,
                        )
                    }
                }

                if(null != commitId) {
                    with("$this/commits/$commitId") {
                        add(
                            "mpc" to this,
                            "mpc-data" to "$this/data"
                        )
                    }
                }
            }
        }

        this
    }
}

@OptIn(InternalAPI::class)
suspend fun HttpClient.submitSparqlUpdate(sparql: String): HttpResponse {
    return post(STORE_UPDATE_URI) {
        headers {
            append(HttpHeaders.Accept, ContentType.Application.Json)
        }
        contentType(ContentType.parse("application/sparql-update"))
        body=sparql
    }
}

@OptIn(InternalAPI::class)
suspend fun HttpClient.submitSparqlConstruct(sparql: String): HttpResponse {
    return post(STORE_QUERY_URI) {
        headers {
            append(HttpHeaders.Accept, ContentType.parse("text/turtle"))
        }
        contentType(ContentType.parse("application/sparql-query"))
        body=sparql
    }
}

@OptIn(InternalAPI::class)
suspend fun HttpClient.submitSparqlQuery(sparql: String): HttpResponse {
    return post(STORE_QUERY_URI) {
        headers {
            append(HttpHeaders.Accept, ContentType.parse("application/sparql-results+json"))
        }
        contentType(ContentType.parse("application/sparql-query"))
        body=sparql
    }
}

@OptIn(InternalAPI::class)
suspend fun HttpClient.executeSparqlAsk(sparqlBgp: String, prefixes: PrefixMapBuilder): Boolean {
    val askResponse = post<HttpResponse>(STORE_QUERY_URI) {
        headers {
            append(HttpHeaders.Accept, ContentType.parse("application/sparql-results+json"))
        }
        contentType(ContentType.parse("application/sparql-query"))
        body="${prefixes.toString()}\n" + "ask { $sparqlBgp }"
    }

    // read response body
    val askResponseText = askResponse.readText()

    // parse response text
    val askResponseJson = Parser.default().parse(StringBuilder(askResponseText)) as JsonObject?

    // cast the ask response
    return askResponseJson?.boolean("boolean")?: false
}

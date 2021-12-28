package org.openmbee

import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.*


val ROOT_CONTEXT = (System.getenv("MMS5_ROOT_CONTEXT")?: "https://mms.openmbee.org/demo").replace("/+$".toRegex(), "")
val STORE_QUERY_URI = System.getenv("MMS5_STORE_QUERY")?: "http://localhost:8081/bigdata/namespace/kb/sparql"
val STORE_UPDATE_URI = System.getenv("MMS5_STORE_UPDATE")?: "http://localhost:8081/bigdata/namespace/kb/sparql"
val SERVICE_ID = System.getenv("MMS5_SERVICE_ID")?: "local-dev-0"

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

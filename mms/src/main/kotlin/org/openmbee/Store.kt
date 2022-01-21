package org.openmbee

import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser
import io.ktor.application.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.*
import org.openmbee.plugins.client


val ROOT_CONTEXT = (System.getenv("MMS5_ROOT_CONTEXT")?: "https://mms.openmbee.org/demo").replace("/+$".toRegex(), "")
val STORE_QUERY_URI = System.getenv("MMS5_STORE_QUERY")?: "http://localhost:8081/bigdata/namespace/kb/sparql"
val STORE_UPDATE_URI = System.getenv("MMS5_STORE_UPDATE")?: "http://localhost:8081/bigdata/namespace/kb/sparql"
val SERVICE_ID = System.getenv("MMS5_SERVICE_ID")?: "local-dev-0"

fun prepareSparql(pattern: String, setup: (Parameterizer.() -> Parameterizer)?): String {
    return if(setup != null) parameterizedSparql(pattern, setup) else pattern
}

suspend fun handleSparqlResponse(response: HttpResponse): String {
    // read response body
    val responseText = response.readText()

    // non-200
    if(!response.status.isSuccess())  {
        throw Non200Response(responseText, response.status)
    }

    return responseText
}

@OptIn(InternalAPI::class)
suspend fun ApplicationCall.submitSparqlUpdate(pattern: String, setup: (Parameterizer.() -> Parameterizer)?=null): String {
    val sparql = prepareSparql(pattern, setup)

    this.application.log.info("SPARQL Update:\n$sparql")

    return handleSparqlResponse(client.post(STORE_UPDATE_URI) {
        headers {
            append(HttpHeaders.Accept, ContentType.Application.Json)
        }
        contentType(RdfContentTypes.SparqlUpdate)
        body=sparql
    })
}

@OptIn(InternalAPI::class)
suspend fun ApplicationCall.submitSparqlConstructOrDescribe(pattern: String, setup: (Parameterizer.() -> Parameterizer)?=null): String {
    val sparql = prepareSparql(pattern, setup)

    this.application.log.info("SPARQL Query CONSTRUCT:\n$sparql")

    return handleSparqlResponse(client.post(STORE_QUERY_URI) {
        headers {
            append(HttpHeaders.Accept, RdfContentTypes.Turtle)
        }
        contentType(RdfContentTypes.SparqlQuery)
        body=sparql
    })
}

@OptIn(InternalAPI::class)
suspend fun ApplicationCall.submitSparqlSelectOrAsk(pattern: String, setup: (Parameterizer.() -> Parameterizer)?=null): String {
    val sparql = prepareSparql(pattern, setup)

    this.application.log.info("SPARQL Query SELECT/ASK:\n$sparql")

    return handleSparqlResponse(client.post(STORE_QUERY_URI) {
        headers {
            append(HttpHeaders.Accept, RdfContentTypes.SparqlResultsJson)
        }
        contentType(RdfContentTypes.SparqlQuery)
        body=sparql
    })
}

@OptIn(InternalAPI::class)
suspend fun ApplicationCall.executeSparqlAsk(sparqlBgp: String, setup: (Parameterizer.() -> Parameterizer)?=null): Boolean {
    val pattern = "ask { $sparqlBgp }"

    val sparql = if(setup != null) parameterizedSparql(pattern, setup) else pattern

    this.application.log.info("SPARQL Query ASK:\n$sparql")

    val askResponse = client.post<HttpResponse>(STORE_QUERY_URI) {
        headers {
            append(HttpHeaders.Accept, RdfContentTypes.SparqlResultsJson)
        }
        contentType(RdfContentTypes.SparqlQuery)
        body = "ask { $sparqlBgp }"
    }

    // read response body
    val askResponseText = askResponse.readText()

    // parse response text
    val askResponseJson = Parser.default().parse(StringBuilder(askResponseText)) as JsonObject?

    // cast the ask response
    return askResponseJson?.boolean("boolean")?: false
}


class IllegalIdException: Exception("Illegal ID string. Must be at least 3 characters long. Letter symbols and special characters '.' '-' '_' allowed.") {}

private val LEGAL_ID_REGEX = """[._\pL-]{3,}""".toRegex()

fun ApplicationCall.assertLegalId(id: String) {
    if(!id.matches(LEGAL_ID_REGEX)) {
        throw IllegalIdException()
    }
}
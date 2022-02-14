package org.openmbee.mms5

import io.ktor.client.statement.*
import io.ktor.http.*
import java.security.MessageDigest


val ROOT_CONTEXT = (System.getenv("MMS5_ROOT_CONTEXT")?: "https://mms.openmbee.org/demo").replace("/+$".toRegex(), "")
val STORE_QUERY_URI = System.getenv("MMS5_STORE_QUERY")?: "http://localhost:8081/bigdata/namespace/kb/sparql"
val STORE_UPDATE_URI = System.getenv("MMS5_STORE_UPDATE")?: "http://localhost:8081/bigdata/namespace/kb/sparql"
val SERVICE_ID = System.getenv("MMS5_SERVICE_ID")?: "local-dev-0"

suspend fun handleSparqlResponse(response: HttpResponse): String {
    // read response body
    val responseText = response.readText()

    // non-200
    if(!response.status.isSuccess())  {
        throw Non200Response(responseText, response.status)
    }

    return responseText
}


class IllegalIdException: Exception("Illegal ID string. Must be at least 3 characters long. Letter symbols and special characters '.' '-' '_' allowed.") {}

private val LEGAL_ID_REGEX = """[._\pL-]{3,}""".toRegex()

fun assertLegalId(id: String) {
    if(!id.matches(LEGAL_ID_REGEX) || id.startsWith("__") || id.length > 256) {
        throw IllegalIdException()
    }
}

fun String.sha256(): String {
    return MessageDigest
        .getInstance("SHA-256")
        .digest(this.toByteArray())
        .fold("") { str, it -> str + "%02x".format(it) }
}
package org.openmbee.mms5

import io.ktor.client.statement.*
import io.ktor.http.*
import java.security.MessageDigest


val ROOT_CONTEXT = (System.getenv("MMS5_ROOT_CONTEXT")?: "https://mms.openmbee.org/demo").replace("/+$".toRegex(), "")
val SERVICE_ID = System.getenv("MMS5_SERVICE_ID")?: "local-dev-0"

suspend fun handleSparqlResponse(response: HttpResponse): String {
    // read response body
    val responseText = response.bodyAsText()

    // non-200
    if(!response.status.isSuccess())  {
        throw Non200Response(responseText, response.status)
    }

    return responseText
}


private val LEGAL_ID_REGEX = """[._\pL0-9-]{3,256}""".toRegex()

fun assertLegalId(id: String, regex: Regex=LEGAL_ID_REGEX) {
    if(!id.matches(regex) || id.startsWith("__")) {
        throw IllegalIdException()
    }
}

fun String.sha256(): String {
    return MessageDigest
        .getInstance("SHA-256")
        .digest(this.toByteArray())
        .fold("") { str, it -> str + "%02x".format(it) }
}
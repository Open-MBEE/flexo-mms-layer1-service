package org.openmbee.flexo.mms

import io.kotest.assertions.ktor.client.shouldHaveStatus
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.utils.io.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openmbee.flexo.mms.util.*

/**
 * Model loads sent straight to the quad-store's Graph Store Protocol endpoint (no store service), with and without
 * a declared Content-Length. Some GSP endpoints ignore chunked bodies, so layer1 always forwards a sized body.
 */
class ModelLoadGraphStore : ModelAny() {
    private val directGsp = mapOf("mms.store-service.url" to "")

    private val turtle = (1..50).joinToString("\n") { """<urn:loaded:$it> <urn:p:value> "value $it" .""" }

    private suspend fun ApplicationTestBuilder.loadedCount(): Int {
        val response = httpPost("$masterBranchPath/query") {
            setSparqlQueryBody("select (count(*) as ?n) { ?s <urn:p:value> ?o }")
        }
        response shouldHaveStatus HttpStatusCode.OK
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["results"]!!.jsonObject["bindings"]!!
            .jsonArray[0].jsonObject["n"]!!.jsonObject["value"]!!.jsonPrimitive.content.toInt()
    }

    init {
        "load a model without a Content-Length" {
            testApplicationWith(directGsp) {
                httpPut("$masterBranchPath/graph") {
                    // no Content-Length: the body is sent chunked
                    setBody(object : OutgoingContent.WriteChannelContent() {
                        override val contentType = ContentType.parse("text/turtle")

                        override suspend fun writeTo(channel: ByteWriteChannel) {
                            channel.writeStringUtf8(turtle)
                        }
                    })
                } shouldHaveStatus HttpStatusCode.OK

                loadedCount() shouldBe 50
            }
        }

        "load a model with a Content-Length" {
            testApplicationWith(directGsp) {
                httpPut("$masterBranchPath/graph") {
                    setTurtleBody(turtle)
                } shouldHaveStatus HttpStatusCode.OK

                loadedCount() shouldBe 50
            }
        }
    }
}

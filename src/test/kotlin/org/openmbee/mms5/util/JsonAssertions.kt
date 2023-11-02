package org.openmbee.mms5.util

import io.kotest.assertions.fail
import io.kotest.assertions.json.shouldBeJsonObject
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.openmbee.mms5.RdfContentTypes

/**
 * Converts a string into a binding result literal JsonElement
 */
var String.bindingLit: JsonElement
    get() = JsonObject(mapOf(
        "type" to JsonPrimitive("literal"),
        "value" to JsonPrimitive(this),
    ))
    set(v) {}

/**
 * Converts a string into a binding result uri JsonElement
 */
var String.bindingUri: JsonElement
    get() = JsonObject(mapOf(
        "type" to JsonPrimitive("uri"),
        "value" to JsonPrimitive(this),
    ))
    set(v) {}

class JsonAsserter(json: JsonElement, var resultsName: String="Unnamed") {
    private val varsActual = json.jsonObject["head"]!!.jsonObject["vars"]!!.jsonArray.toTypedArray()
        .map { it.jsonPrimitive.content }.toCollection(ArrayList())

    private val bindingsActual = json.jsonObject["results"]!!.jsonObject["bindings"]!!.jsonArray.toTypedArray()
        .toCollection(ArrayList())

    private val varsExpect = hashSetOf<String>()
    private val bindings = arrayListOf<JsonObject>()

    /**
     * Asserts the given binding is next in the expected results JSON, and checks the variable names is in head
     */
    fun binding(vararg pairs: Pair<String, JsonElement>) {
        val row = hashMapOf<String, JsonElement>()

        // each pair in assertion
        for((varName, element) in pairs) {
            // add to expected set
            varsExpect.add(varName)

            // check var is present in actual
            varsActual.shouldContain(varName)

            // save element to hash map
            row[varName] = element
        }

        // assert the next element equals this binding
        bindingsActual[0].toString() shouldBe JsonObject(row).toString()

        // remove matched binding
        bindingsActual.removeFirst()
    }

    /**
     * Asserts no other bindings exist in the results
     */
    fun assertBindingsEmpty() {
        if(bindingsActual.isNotEmpty()) {
            fail("\"$resultsName\" SPARQL results JSON has extraneous bindings:\n"+bindingsActual.joinToString(",\n") { it.toString() })
        }
    }

    /**
     * Asserts no other vars exist in "head"
     */
    fun assertVarsCovered() {
        for(varName in varsActual) {
            if(!varsExpect.contains(varName)) {
                fail("\"$resultsName\" SPARQL results JSON has extraneous variable in \"head\": $varName")
            }
        }
    }

    fun assertExclusive() {
        assertBindingsEmpty()
        assertVarsCovered()
    }
}

infix fun TestApplicationResponse.equalsSparqlResults(build: JsonAsserter.() -> Unit) {
    this shouldHaveStatus HttpStatusCode.OK

    // assert content-type header (ignore charset if present)
    this.headers[HttpHeaders.ContentType].shouldStartWith(RdfContentTypes.SparqlResultsJson.contentType)

    // load str
    val jsonStr = this.content.toString()

    // json object
    jsonStr.shouldBeJsonObject()

    // parse
    val jsonObj = Json.parseToJsonElement(jsonStr)

    // apply assertions
    JsonAsserter(jsonObj).apply { build() }.assertExclusive()
}

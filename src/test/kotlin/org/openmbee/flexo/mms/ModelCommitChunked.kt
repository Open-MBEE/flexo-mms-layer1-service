package org.openmbee.flexo.mms

import io.kotest.assertions.ktor.client.shouldHaveStatus
import io.kotest.matchers.shouldBe
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openmbee.flexo.mms.util.*

/**
 * Commits with the request limits used for Virtuoso set very low, so every commit is split into many operations
 * and applied through the scratch graph.
 */
class ModelCommitChunked : ModelAny() {
    private val tinyLimits = mapOf(
        "mms.application.max-triples-per-update-block" to "2",
        "mms.application.max-update-request-kib" to "1",
    )

    // the shape flexo-sysmlv2 sends: delete the changed elements' triples, then insert their new state
    private fun sysmlv2StyleCommit(elements: IntRange, label: String): String {
        val values = elements.joinToString(" ") { "<urn:element:$it>" }
        val inserts = elements.joinToString("\n") {
            """<urn:element:$it> <urn:p:name> "$label $it" ; <urn:p:index> $it ; a <urn:c:Element> ."""
        }
        return """
            delete {
                ?element_n ?element_p ?element_o .
            } where {
                values ?element_n { $values }
                optional {
                    ?element_n ?element_p ?element_o .
                }
            } ;
            insert data {
                $inserts
            }
        """.trimIndent()
    }

    private suspend fun ApplicationTestBuilder.countTriples(filter: String = ""): Int {
        val response = httpPost("$masterBranchPath/query") {
            setSparqlQueryBody("select (count(*) as ?n) { ?s ?p ?o $filter }")
        }
        response shouldHaveStatus HttpStatusCode.OK
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["results"]!!.jsonObject["bindings"]!!
            .jsonArray[0].jsonObject["n"]!!.jsonObject["value"]!!.jsonPrimitive.content.toInt()
    }

    init {
        "a large commit split into many requests produces the same model" {
            testApplicationWith(tinyLimits) {
                httpPost("$masterBranchPath/update") {
                    setSparqlUpdateBody(sysmlv2StyleCommit(1..20, "first"))
                } shouldHaveStatus HttpStatusCode.Created

                countTriples() shouldBe 60

                // replace some elements and add new ones; the rest must be untouched
                httpPost("$masterBranchPath/update") {
                    setSparqlUpdateBody(sysmlv2StyleCommit(15..30, "second"))
                } shouldHaveStatus HttpStatusCode.Created

                countTriples() shouldBe 90
                countTriples("""filter(?p = <urn:p:name> && strstarts(str(?o), "second"))""") shouldBe 16
                countTriples("""filter(?p = <urn:p:name> && strstarts(str(?o), "first"))""") shouldBe 14
            }
        }

        "a commit that fits in one request is applied directly" {
            testApplicationWith(mapOf("mms.application.max-triples-per-update-block" to "1000")) {
                httpPost("$masterBranchPath/update") {
                    setSparqlUpdateBody(sysmlv2StyleCommit(1..5, "small"))
                } shouldHaveStatus HttpStatusCode.Created

                countTriples() shouldBe 15
            }
        }

        "a commit that deletes most elements keeps only the rest" {
            testApplicationWith(tinyLimits) {
                httpPost("$masterBranchPath/update") {
                    setSparqlUpdateBody(sysmlv2StyleCommit(1..6, "first"))
                } shouldHaveStatus HttpStatusCode.Created

                httpPost("$masterBranchPath/update") {
                    setSparqlUpdateBody("""
                        delete {
                            ?element_n ?element_p ?element_o .
                        } where {
                            values ?element_n { ${(1..5).joinToString(" ") { "<urn:element:$it>" }} }
                            optional {
                                ?element_n ?element_p ?element_o .
                            }
                        }
                    """.trimIndent())
                } shouldHaveStatus HttpStatusCode.Created

                // (deleting every triple is not tested: an empty model graph is a known Fuseki limitation, see ModelLoad)
                countTriples() shouldBe 3
            }
        }
    }
}

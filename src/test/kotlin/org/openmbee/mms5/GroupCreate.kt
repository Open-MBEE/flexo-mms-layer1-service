package org.openmbee.mms5

import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.http.*
import io.ktor.server.testing.*
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.mms5.util.*
import org.slf4j.LoggerFactory
import java.net.URLEncoder

fun TriplesAsserter.validateGroupTriples(
    createResponse: TestApplicationResponse,
    groupId: String,
    extraPatterns: List<PairPattern> = listOf()
) {
    val groupIri = localIri("/groups/$groupId")

    // org triples
    subject(groupIri) {
        exclusivelyHas(
            RDF.type exactly MMS.Group,
            MMS.id exactly groupId,
            MMS.etag startsWith "",
            *extraPatterns.toTypedArray()
        )
    }
}

fun TriplesAsserter.validateCreatedGroupTriples(
    createResponse: TestApplicationResponse,
    groupId: String,
    extraPatterns: List<PairPattern> = listOf()
) {
    validateGroupTriples(createResponse, groupId, extraPatterns)

    // transaction
    validateTransaction()

    // inspect
    subject("urn:mms:inspect") { ignoreAll() }
}


class GroupCreate : CommonSpec() {
    open val logger = LoggerFactory.getLogger(PolicyCreate::class.java)

    val groupId = "ldap/cn=all.personnel,ou=personnel"
    val groupPath = "/groups/${URLEncoder.encode(groupId, "UTF-8")}"

    val groupTitle = "Test Group"
    val validGroupBody = withAllTestPrefixes("""
        <>
            dct:title "${groupTitle}"@en ;
            .
    """.trimIndent())

    init {
        "group id with slash".config(tags=setOf(NoAuth)) {
            withTest {
                httpPut("$groupPath/non-existant-path/foobar") {
                    setTurtleBody(validGroupBody)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }

        "reject invalid group id".config(tags=setOf(NoAuth)) {
            withTest {
                httpPut("$groupPath with invalid id") {
                    setTurtleBody(validGroupBody)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.BadRequest
                }
            }
        }

        mapOf(
            "rdf:type" to "mms:NotGroup",
            "mms:id" to "\"not-$groupId\"",
        ).forEach { (pred, obj) ->
            "reject wrong $pred".config(tags=setOf(NoAuth)) {
                withTest {
                    httpPut(groupPath) {
                        setTurtleBody("""
                            $validGroupBody
                            <> $pred $obj .
                        """.trimIndent())
                    }.apply {
                        response shouldHaveStatus HttpStatusCode.BadRequest
                    }
                }
            }
        }

        "create valid group".config(tags=setOf(NoAuth)) {
            withTest {
                httpPut(groupPath) {
                    setTurtleBody(validGroupBody)
                }.apply {
                    response.headers[HttpHeaders.ETag].shouldNotBeBlank()

                    response includesTriples  {
                        modelName = it

                        validateCreatedGroupTriples(response, groupId, listOf(
                            DCTerms.title exactly groupTitle.en
                        ))
                    }
                }
            }
        }
    }
}
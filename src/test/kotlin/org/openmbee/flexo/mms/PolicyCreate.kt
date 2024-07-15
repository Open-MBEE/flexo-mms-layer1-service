package org.openmbee.flexo.mms


import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.http.*
import io.ktor.server.testing.*
import org.apache.jena.rdf.model.Resource
import org.apache.jena.vocabulary.RDF
import org.apache.jena.vocabulary.XSD
import org.openmbee.flexo.mms.util.*
import org.slf4j.LoggerFactory


fun TriplesAsserter.validatePolicyTriples(
    createResponse: TestApplicationResponse,
    policyId: String,
    subjectPath: String,
    scopePath: String,
    roleNodes: List<Resource>,
    extraPatterns: List<PairPattern> = listOf()
) {
    val policyIri = localIri("/policies/$policyId")

    // org triples
    subject(policyIri) {
        exclusivelyHas(
            RDF.type exactly MMS.Policy,
            MMS.id exactly policyId,
            MMS.etag startsWith "",
            MMS.subject exactly localIri(subjectPath).iri,
            MMS.scope exactly localIri(scopePath).iri,
            MMS.role exactly roleNodes,
            *extraPatterns.toTypedArray()
        )
    }
}

fun TriplesAsserter.validateCreatedPolicyTriples(
    createResponse: TestApplicationResponse,
    policyId: String,
    subjectPath: String,
    scopePath: String,
    roleNodes: List<Resource>,
    extraPatterns: List<PairPattern> = listOf()
) {
    createResponse shouldHaveStatus HttpStatusCode.Created
    validatePolicyTriples(createResponse, policyId, subjectPath, scopePath, roleNodes, extraPatterns)

    // // auto policy
    // matchOneSubjectTerseByPrefix("m-policy:AutoPolicyOwner") {
    //     includes(
    //         RDF.type exactly MMS.Policy,
    //     )
    // }

    val policyIri = localIri("/policies/$policyId")

    // transaction
    validateTransaction()

    // inspect
    subject("urn:mms:inspect") { ignoreAll() }
}

class PolicyCreate : CommonSpec() {
    open val logger = LoggerFactory.getLogger(PolicyCreate::class.java)

    val policyId = "TestPolicy"
    val policyPath = "/policies/$policyId"

    val testUserPath = "/users/test"
    val clusterScopePath = "/"
    val testRoleNodes = listOf(
        MMS_OBJECT.ROLE.AdminAccessControl
    )

    val validPolicyBody = """
        <>
            mms:subject <${localIri(testUserPath)}> ;
            mms:scope <${localIri(clusterScopePath)}> ;
            mms:role ${testRoleNodes.joinToString(", ") { "<${it.uri}>" }} ;
            .
    """.trimIndent()

    init {
        "reject invalid policy id".config(tags=setOf(NoAuth)) {
            withTest {
                httpPut("$policyPath with invalid id") {
                    setTurtleBody(withAllTestPrefixes(validPolicyBody))
                }.apply {
                    response shouldHaveStatus HttpStatusCode.BadRequest
                }
            }
        }

        mapOf(
            "rdf:type" to "mms:NotPolicy",
            "mms:id" to "\"not-$policyId\"",
        ).forEach { (pred, obj) ->
            "reject wrong $pred".config(tags=setOf(NoAuth)) {
                withTest {
                    httpPut(policyPath) {
                        setTurtleBody(withAllTestPrefixes("""
                            $validPolicyBody
                            <> $pred $obj .
                        """.trimIndent()))
                    }.apply {
                        response shouldHaveStatus HttpStatusCode.BadRequest
                    }
                }
            }
        }

        "create valid policy".config(tags=setOf(NoAuth)) {
            withTest {
                httpPut(policyPath) {
                    setTurtleBody(withAllTestPrefixes(validPolicyBody))
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response.headers[HttpHeaders.ETag].shouldNotBeBlank()

                    response exclusivelyHasTriples {
                        modelName = it

                        validateCreatedPolicyTriples(response, policyId, testUserPath, clusterScopePath, testRoleNodes)
                    }
                }
            }
        }
    }
}
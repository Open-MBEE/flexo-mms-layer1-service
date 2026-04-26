package org.openmbee.flexo.mms

import io.kotest.assertions.ktor.client.shouldHaveStatus
import io.kotest.core.test.TestCase
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.apache.jena.rdf.model.ResourceFactory
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.util.*
import org.slf4j.LoggerFactory


fun TriplesAsserter.validateCollectionTriples(
    collectionId: String,
    orgId: String,
    collectionName: String,
    extraPatterns: List<PairPattern> = listOf()
) {
    val collectionIri = localIri("/orgs/$orgId/collections/$collectionId")

    subject(collectionIri) {
        includes(
            RDF.type exactly MMS.Collection,
            MMS.id exactly collectionId,
            MMS.org exactly localIri("/orgs/$orgId").iri,
            DCTerms.title exactly collectionName.en,
            MMS.etag startsWith "",
        )
    }
}

fun TriplesAsserter.validateCreatedCollectionTriples(
    createResponse: HttpResponse,
    collectionId: String,
    orgId: String,
    collectionName: String,
    extraPatterns: List<PairPattern> = listOf()
) {
    validateCollectionTriples(collectionId, orgId, collectionName, extraPatterns)

    // auto policy
    matchOneSubjectTerseByPrefix("m-policy:AutoCollectionOwner") {
        includes(
            RDF.type exactly MMS.Policy,
        )
    }

    // transaction
    validateTransaction(orgPath = "/orgs/$orgId")
}




class CollectionLdpDc : CollectionAny() {
    init {
        "PUT collection - create valid collection" {
            testApplication {
                httpPut(demoCollectionPath) {
                    setTurtleBody(withAllTestPrefixes(validCollectionBody))
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Created
                }
            }
        }

        "PUT collection - replace existing collection" {
            testApplication {
                // create initial collection
                httpPut(demoCollectionPath) {
                    setTurtleBody(withAllTestPrefixes(validCollectionBody))
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Created
                }

                // replace it
                httpPut(demoCollectionPath) {
                    setTurtleBody(withAllTestPrefixes("""
                        <> dct:title "Updated Collection"@en .
                        <> mms:collects <$demoBranchRef> .
                    """.trimIndent()))
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                }
            }
        }

        "GET collection - non-existent" {
            testApplication {
                httpGet(demoCollectionPath) {}.apply {
                    this shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }

        "GET collection - valid" {
            testApplication {
                httpPut(demoCollectionPath) {
                    setTurtleBody(withAllTestPrefixes(validCollectionBody))
                }

                httpGet(demoCollectionPath) {}.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this includesTriples {
                        validateCollectionTriples(demoCollectionId, demoOrgId, demoCollectionName)
                    }
                }
            }
        }

        "HEAD collection - valid" {
            testApplication {
                httpPut(demoCollectionPath) {
                    setTurtleBody(withAllTestPrefixes(validCollectionBody))
                }

                httpHead(demoCollectionPath) {}.apply {
                    this shouldHaveStatus HttpStatusCode.NoContent
                }
            }
        }

        "GET collections - all collections" {
            testApplication {
                httpPut(demoCollectionPath) {
                    setTurtleBody(withAllTestPrefixes(validCollectionBody))
                }

                httpGet(basePathCollections) {}.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                }
            }
        }

        "PUT collection - reject missing mms:collects" {
            testApplication {
                httpPut(demoCollectionPath) {
                    setTurtleBody(withAllTestPrefixes("""
                        <> dct:title "$demoCollectionName"@en .
                    """.trimIndent()))
                }.apply {
                    this shouldHaveStatus HttpStatusCode.BadRequest
                }
            }
        }

        "POST collections - create valid collection" {
            testApplication {
                httpPost(basePathCollections) {
                    header(HttpHeaders.SLUG, demoCollectionId)
                    setTurtleBody(withAllTestPrefixes(validCollectionBody))
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Created
                }
            }
        }
    }
}

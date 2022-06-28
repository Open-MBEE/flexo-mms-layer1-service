package org.openmbee.mms5

import io.kotest.assertions.ktor.shouldHaveStatus
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.http.*
import io.ktor.response.*
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.mms5.util.*
import java.util.*

class BranchCreate : BranchAny() {
    init {
        "reject invalid branch id" {
            withTest {
                httpPut("/orgs/$orgId/repos/$repoId/branches/bad branch id") {
                    setTurtleBody(validBranchBody)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.BadRequest
                }
            }
        }

        mapOf(
            "rdf:type" to "mms:NotBranch",
            "mms:id" to "\"not-$branchId\"",
            "mms:etag" to "\"${UUID.randomUUID()}\"",
            "mms:ref" to "<${localIri("/orgs/$orgId/repos/$repoId/branches/nosuchbranch")}>"
        ).forEach { (pred, obj) ->
            "reject wrong $pred" {
                withTest {
                    httpPut(branchPath) {
                        setTurtleBody("""
                            <> dct:title "$branchName"@en .
                            <> $pred $obj .
                        """.trimIndent())
                    }.apply {
                        response shouldHaveStatus HttpStatusCode.BadRequest
                    }
                }
            }
        }

        "create valid branch" {
            val update = updateModel("""
                insert { <somesub> <somepred> 5 .}
            """.trimIndent(), "master", repoId, orgId)
            withTest {
                httpPut(branchPath) {
                    setTurtleBody(
                        """
                        $validBranchBody
                        <> <$arbitraryPropertyIri> "$arbitraryPropertyValue" .
                    """.trimIndent()
                    )
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response.headers[HttpHeaders.ETag].shouldNotBeBlank()

                    response exclusivelyHasTriples {
                        modelName = "response"

                        subject(localIri(branchPath)) {
                            exclusivelyHas(
                                RDF.type exactly MMS.Branch,
                                MMS.id exactly branchId,
                                DCTerms.title exactly branchName.en,
                                MMS.etag exactly response.headers[HttpHeaders.ETag]!!,
                                MMS.commit exactly update.response.headers[HttpHeaders.ETag]!!,
                                arbitraryPropertyIri.toPredicate exactly arbitraryPropertyValue
                            )
                        }
                    }
                }
            }
        }
    }
}

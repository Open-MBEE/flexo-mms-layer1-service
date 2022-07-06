package org.openmbee.mms5

import io.kotest.assertions.ktor.shouldHaveStatus
import io.kotest.core.test.TestCase
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.http.*
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.mms5.util.*
import org.slf4j.LoggerFactory

class RepoCreate : RepoAny() {
    init {
        "reject invalid repo id" {
            withTest {
                httpPut("/orgs/$orgId/repo/invalid repo id") {
                    setTurtleBody(validRepoBody)
                }.apply {
                    response shouldHaveStatus 400
                }
            }
        }

        "create valid repo" {
            withTest {
                httpPut(repoPath) {
                    setTurtleBody(
                        """
                        $validRepoBody
                        <> <$arbitraryPropertyIri> "$arbitraryPropertyValue" .
                    """.trimIndent()
                    )
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response.headers[HttpHeaders.ETag].shouldNotBeBlank()

                    response exclusivelyHasTriples {
                        modelName = "response"

                        subject(localIri(repoPath)) {
                            exclusivelyHas(
                                RDF.type exactly MMS.Repo,
                                MMS.id exactly repoId,
                                MMS.org exactly localIri(orgPath).iri,
                                DCTerms.title exactly repoName.en,
                                MMS.etag exactly response.headers[HttpHeaders.ETag]!!,
                                arbitraryPropertyIri.toPredicate exactly arbitraryPropertyValue,
                            )
                        }
                    }
                }
            }
        }
    }
}
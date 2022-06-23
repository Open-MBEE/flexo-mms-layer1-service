package org.openmbee.mms5

import io.kotest.assertions.ktor.shouldHaveStatus
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.http.*
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.mms5.util.*


class RepoTestsKotest : KotestCommon() {
    val orgId = "repoTests"
    val orgPath = "/orgs/$orgId"
    val orgName = "Repo Tests"

    val repoId = "new-repo"
    val repoName = "New Repo"
    val repoPath = "$orgPath/repos/$repoId"

    val arbitraryPropertyIri = "https://demo.org/custom/prop"
    val arbitraryPropertyValue = "test"


    init {
        "reject invalid repo id" {
            createOrg(orgId, orgName)

            withTest {
                put(repoPath) {
                    setTurtleBody("""
                        <> dct:title "$repoName"@en ;
                        <$arbitraryPropertyIri> "$arbitraryPropertyValue ;
                    """.trimIndent())
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

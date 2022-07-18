package org.openmbee.mms5

import io.kotest.assertions.ktor.shouldHaveStatus
import io.kotest.core.test.TestCase
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.http.*
import org.apache.jena.sparql.vocabulary.FOAF
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.mms5.util.*
import org.slf4j.LoggerFactory

class RepoUpdate : RepoAny() {
    init {
        "patch repo insert" {
            createRepo(orgPath, repoId, repoName)

            withTest {
                httpPatch(repoPath) {
                    setSparqlUpdateBody(
                        """
                        insert {
                            <> foaf:homepage <https://www.openmbee.org/> .
                        }
                        where {
                            <> dct:title "$repoName"@en .
                        }
                    """.trimIndent()
                    )
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK

                    response exclusivelyHasTriples {
                        validateRepoTriples(
                            response, repoId, repoName, orgPath, listOf(
                                FOAF.homepage exactly "https://www.openmbee.org/".iri
                            )
                        )
                    }
                }
            }
        }

        "patch repo insert failed condition" {
            createRepo(orgPath, repoId, repoName)

            withTest {
                httpPatch(repoPath) {
                    setSparqlUpdateBody(
                        """
                        insert {
                            <> foaf:homepage <https://www.openmbee.org/> .
                        }
                        where {
                            <> dct:title "Not $repoName"@en .
                        }
                    """.trimIndent()
                    )
                }.apply {
                    response shouldHaveStatus HttpStatusCode.PreconditionFailed
                }
            }
        }
    }
}

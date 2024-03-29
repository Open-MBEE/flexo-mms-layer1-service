package org.openmbee.flexo.mms


import io.kotest.core.test.TestCase
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.http.*
import org.apache.jena.rdf.model.Literal
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.apache.jena.vocabulary.XSD
import org.openmbee.flexo.mms.util.*
import org.slf4j.LoggerFactory

class RepoCreate : RepoAny() {
    init {
        "reject invalid repo id".config(tags=setOf(NoAuth)) {
            withTest {
                httpPut("$repoPath with invalid id") {
                    setTurtleBody(withAllTestPrefixes(validRepoBody))
                }.apply {
                    response shouldHaveStatus 400
                }
            }
        }

        "create valid repo" {
            withTest {
                httpPut(repoPath) {
                    setTurtleBody(withAllTestPrefixes("""
                        $validRepoBody
                        <> <$arbitraryPropertyIri> "$arbitraryPropertyValue" .
                    """.trimIndent()))
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response.headers[HttpHeaders.ETag].shouldNotBeBlank()

                    response includesTriples {
                        modelName = "CreateValidRepo"

                        validateCreatedRepoTriples(response, repoId, repoName, orgPath, listOf(
                            arbitraryPropertyIri.toPredicate exactly arbitraryPropertyValue,
                        ))

                        // // auto policy for master branch
                        // matchOneSubjectTerseByPrefix("m-policy:AutoBranchOwner.") {
                        //     includes(
                        //         RDF.type exactly MMS.Policy,
                        //     )
                        // }

                        // // master branch
                        // subject(localIri("$repoPath/branches/master")) {
                        //     includes(
                        //         RDF.type exactly MMS.Branch,
                        //         MMS.id exactly "master",
                        //         DCTerms.title exactly "Master".en,
                        //         MMS.etag startsWith "",
                        //         MMS.commit startsWith localIri("$commitsPath/").iri,
                        //     )
                        // }

                    }
                }
            }
        }
    }
}
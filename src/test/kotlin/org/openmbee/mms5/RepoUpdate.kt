package org.openmbee.mms5


import io.ktor.http.*
import org.apache.jena.sparql.vocabulary.FOAF
import org.apache.jena.vocabulary.RDF
import org.apache.jena.vocabulary.XSD
import org.openmbee.mms5.util.*

class RepoUpdate : RepoAny() {
    init {
        "patch repo insert" {
            createRepo(orgPath, repoId, repoName)

            withTest {
                httpPatch(repoPath) {
                    setSparqlUpdateBody(withAllTestPrefixes("""
                        insert {
                            <> foaf:homepage <https://www.openmbee.org/> .
                        }
                        where {
                            <> dct:title "$repoName"@en .
                        }
                    """.trimIndent()))
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK

                    response includesTriples  {
                        validateRepoTriples(
                            repoId, repoName, orgPath, listOf(
                                FOAF.homepage exactly "https://www.openmbee.org/".iri
                            )
                        )

                        // transaction
                        validateTransaction(repoPath=repoPath)
                    }
                }
            }
        }

        "patch repo insert failed condition" {
            createRepo(orgPath, repoId, repoName)

            withTest {
                httpPatch(repoPath) {
                    setSparqlUpdateBody(withAllTestPrefixes("""
                        insert {
                            <> foaf:homepage <https://www.openmbee.org/> .
                        }
                        where {
                            <> dct:title "Not $repoName"@en .
                        }
                    """.trimIndent()))
                }.apply {
                    response shouldHaveStatus HttpStatusCode.PreconditionFailed
                }
            }
        }
    }
}

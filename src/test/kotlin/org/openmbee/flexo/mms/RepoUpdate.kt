package org.openmbee.flexo.mms


import io.ktor.http.*
import org.apache.jena.sparql.vocabulary.FOAF
import org.openmbee.flexo.mms.util.*

class RepoUpdate : RepoAny() {
    init {
        "patch repo insert" {
            createRepo(demoOrgPath, demoRepoId, demoRepoName)

            withTest {
                httpPatch(demoRepoPath) {
                    setSparqlUpdateBody(withAllTestPrefixes("""
                        insert {
                            <> foaf:homepage <https://www.openmbee.org/> .
                        }
                        where {
                            <> dct:title "$demoRepoName"@en .
                        }
                    """.trimIndent()))
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK

                    response includesTriples  {
                        validateRepoTriples(
                            demoRepoId, demoRepoName, demoOrgPath, listOf(
                                FOAF.homepage exactly "https://www.openmbee.org/".iri
                            )
                        )

                        // transaction
                        validateTransaction(repoPath=demoRepoPath)
                    }
                }
            }
        }

        "patch repo insert failed condition" {
            createRepo(demoOrgPath, demoRepoId, demoRepoName)

            withTest {
                httpPatch(demoRepoPath) {
                    setSparqlUpdateBody(withAllTestPrefixes("""
                        insert {
                            <> foaf:homepage <https://www.openmbee.org/> .
                        }
                        where {
                            <> dct:title "Not $demoRepoName"@en .
                        }
                    """.trimIndent()))
                }.apply {
                    response shouldHaveStatus HttpStatusCode.PreconditionFailed
                }
            }
        }
    }
}

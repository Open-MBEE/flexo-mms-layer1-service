package org.openmbee.flexo.mms


import io.ktor.http.*
import org.apache.jena.sparql.vocabulary.FOAF
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.util.*

class BranchUpdate : RefAny() {
    init {
        "patch branch" {
            createBranch(demoRepoPath, "master", demoBranchId, demoBranchName)

            withTest {
                httpPatch(demoBranchPath) {
                    setSparqlUpdateBody(
                        withAllTestPrefixes(
                            """
                        insert {
                            <> foaf:homepage <https://www.openmbee.org/> .
                        }
                        where {
                            <> dct:title "$demoBranchName"@en .
                        }
                    """.trimIndent()
                        )
                    )
                }.apply {
                    response includesTriples {
                        subject(localIri(demoBranchPath)) {
                            includes(
                                RDF.type exactly MMS.Branch,
                                MMS.id exactly demoBranchId,
                                DCTerms.title exactly demoBranchName.en,
                                FOAF.homepage exactly model.createResource("https://www.openmbee.org/")
                            )
                        }
                    }
                }
            }
        }

        "all branches rejects other methods" {
            createBranch(demoRepoPath, "master", demoBranchId, demoBranchName)

            withTest {
                onlyAllowsMethods("$demoRepoPath/branches", setOf(
                    HttpMethod.Head,
                    HttpMethod.Get,
                    HttpMethod.Post,
                ))
            }
        }

        "branch rejects other methods" {
            createBranch(demoRepoPath, "master", demoBranchId, demoBranchName)

            withTest {
                onlyAllowsMethods(demoBranchPath, setOf(
                    HttpMethod.Head,
                    HttpMethod.Get,
                    HttpMethod.Put,
                    HttpMethod.Patch,
                    HttpMethod.Delete,
                ))
            }
        }
    }
}

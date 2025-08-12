package org.openmbee.flexo.mms


import io.ktor.http.*
import io.ktor.server.testing.*
import org.apache.jena.sparql.vocabulary.FOAF
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.util.*

class BranchUpdate : RefAny() {
    init {
        "patch branch" {
            testApplication {
                createBranch(demoRepoPath, "master", demoBranchId, demoBranchName)
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
                    this includesTriples {
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
            testApplication {
                createBranch(demoRepoPath, "master", demoBranchId, demoBranchName)
                onlyAllowsMethods("$demoRepoPath/branches", setOf(
                    HttpMethod.Head,
                    HttpMethod.Get,
                    HttpMethod.Post,
                ))
            }
        }

        "branch rejects other methods" {
            testApplication {
                createBranch(demoRepoPath, "master", demoBranchId, demoBranchName)
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

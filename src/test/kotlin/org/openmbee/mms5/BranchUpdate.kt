package org.openmbee.mms5

import io.kotest.assertions.ktor.shouldHaveStatus
import io.ktor.http.*
import org.apache.jena.sparql.vocabulary.FOAF
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.mms5.util.*

class BranchUpdate : BranchAny() {
    init {
        "patch branch" {
            createBranch(branchId, branchName, "master", repoId, orgId)

            withTest {
                httpPatch(branchPath) {
                    setSparqlUpdateBody(
                        """
                        @prefix foaf: <http://xmlns.com/foaf/0.1/> .
                        insert {
                            <> foaf:homepage <https://www.openmbee.org/> .
                        }
                        where {
                            <> dct:title "$branchName"@en .
                        }
                    """.trimIndent()
                    )
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK

                    response exclusivelyHasTriples {
                        subject(localIri(branchPath)) {
                            exclusivelyHas(
                                RDF.type exactly MMS.Branch,
                                MMS.id exactly branchId,
                                DCTerms.title exactly branchName.en,
                                FOAF.homepage exactly model.createResource("https://www.openmbee.org/")
                            )
                        }
                    }
                }
            }
        }
    }
}

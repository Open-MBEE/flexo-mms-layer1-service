package org.openmbee.flexo.mms


import org.apache.jena.sparql.vocabulary.FOAF
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.util.*

class BranchUpdate : RefAny() {
    init {
        "patch branch" {
            createBranch(demoRepoPath, "master", branchId, branchName)

            withTest {
                httpPatch(branchPath) {
                    setSparqlUpdateBody(withAllTestPrefixes("""
                        insert {
                            <> foaf:homepage <https://www.openmbee.org/> .
                        }
                        where {
                            <> dct:title "$branchName"@en .
                        }
                    """.trimIndent()))
                }.apply {
                    response includesTriples {
                        subject(localIri(branchPath)) {
                            includes(
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

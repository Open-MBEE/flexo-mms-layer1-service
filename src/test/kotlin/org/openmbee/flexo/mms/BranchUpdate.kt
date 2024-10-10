package org.openmbee.flexo.mms


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
                    setSparqlUpdateBody(withAllTestPrefixes("""
                        insert {
                            <> foaf:homepage <https://www.openmbee.org/> .
                        }
                        where {
                            <> dct:title "$demoBranchName"@en .
                        }
                    """.trimIndent()))
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
    }
}

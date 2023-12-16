package org.openmbee.flexo.mms



import io.ktor.http.*
import org.apache.jena.sparql.vocabulary.FOAF
import org.openmbee.flexo.mms.util.*

class OrgUpdate : OrgAny() {
    init {
        "patch org insert" {
            createOrg(demoOrgId, demoOrgName)

            withTest {
                httpPatch(demoOrgPath) {
                    setSparqlUpdateBody(withAllTestPrefixes("""
                        insert {
                            <> foaf:homepage <https://www.openmbee.org/> .
                        }
                        where {
                            <> dct:title "$demoOrgName"@en .
                        }
                    """.trimIndent()))
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK

                    response exclusivelyHasTriples {
                        validateCreatedOrgTriples(
                            response, demoOrgId, demoOrgName, listOf(
                                FOAF.homepage exactly "https://www.openmbee.org/".iri
                            )
                        )
                    }
                }
            }
        }

        "patch org insert failed condition" {
            createOrg(demoOrgId, demoOrgName)

            withTest {
                httpPatch(demoOrgPath) {
                    setSparqlUpdateBody(withAllTestPrefixes("""
                        insert {
                            <> foaf:homepage <https://www.openmbee.org/> .
                        }
                        where {
                            <> dct:title "Not $demoOrgName"@en .
                        }
                    """.trimIndent()))
                }.apply {
                    response shouldHaveStatus HttpStatusCode.PreconditionFailed
                }
            }
        }
    }
}
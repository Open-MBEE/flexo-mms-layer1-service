package org.openmbee.flexo.mms



import io.ktor.http.*
import org.apache.jena.sparql.vocabulary.FOAF
import org.openmbee.flexo.mms.util.*
import java.util.*

class OrgUpdate : OrgAny() {
    init {
        "patch org insert" {
            createOrg(orgId, orgName)

            withTest {
                httpPatch(orgPath) {
                    setSparqlUpdateBody(withAllTestPrefixes("""
                        insert {
                            <> foaf:homepage <https://www.openmbee.org/> .
                        }
                        where {
                            <> dct:title "$orgName"@en .
                        }
                    """.trimIndent()))
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK

                    response exclusivelyHasTriples {
                        validateCreatedOrgTriples(
                            response, orgId, orgName, listOf(
                                FOAF.homepage exactly "https://www.openmbee.org/".iri
                            )
                        )
                    }
                }
            }
        }

        "patch org insert failed condition" {
            createOrg(orgId, orgName)

            withTest {
                httpPatch(orgPath) {
                    setSparqlUpdateBody(withAllTestPrefixes("""
                        insert {
                            <> foaf:homepage <https://www.openmbee.org/> .
                        }
                        where {
                            <> dct:title "Not $orgName"@en .
                        }
                    """.trimIndent()))
                }.apply {
                    response shouldHaveStatus HttpStatusCode.PreconditionFailed
                }
            }
        }
    }
}
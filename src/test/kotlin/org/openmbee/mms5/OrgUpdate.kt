package org.openmbee.mms5



import io.ktor.http.*
import org.apache.jena.sparql.vocabulary.FOAF
import org.openmbee.mms5.util.*
import java.util.*

class OrgUpdate : OrgAny() {
    init {
        "patch org insert" {
            createOrg(orgId, orgName)

            withTest {
                httpPatch(orgPath) {
                    setSparqlUpdateBody(
                        """
                        insert {
                            <> foaf:homepage <https://www.openmbee.org/> .
                        }
                        where {
                            <> dct:title "$orgName"@en .
                        }
                    """.trimIndent()
                    )
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK

                    response exclusivelyHasTriples {
                        validateOrgTriples(
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
                    setSparqlUpdateBody(
                        """
                        insert {
                            <> foaf:homepage <https://www.openmbee.org/> .
                        }
                        where {
                            <> dct:title "Not $orgName"@en .
                        }
                    """.trimIndent()
                    )
                }.apply {
                    response shouldHaveStatus HttpStatusCode.PreconditionFailed
                }
            }
        }
    }
}
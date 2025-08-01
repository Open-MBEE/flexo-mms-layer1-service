package org.openmbee.flexo.mms

import io.ktor.client.statement.*
import io.ktor.server.testing.*
import org.apache.jena.rdf.model.ResourceFactory
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.util.*
import org.slf4j.LoggerFactory

fun TriplesAsserter.validateOrgTriples(
    createResponse: HttpResponse,
    orgId: String,
    orgName: String,
    extraPatterns: List<PairPattern> = listOf()
) {
    val orgIri = localIri("/orgs/$orgId")

    // org triples
    subject(orgIri) {
        exclusivelyHas(
            RDF.type exactly MMS.Org,
            MMS.id exactly orgId,
            DCTerms.title exactly orgName.en,
            // MMS.etag exactly createResponse.headers[HttpHeaders.ETag]!!,
            MMS.etag startsWith "",
            MMS.created startsWith "",
            MMS.createdBy exactly ResourceFactory.createResource(userIri("root")),
            *extraPatterns.toTypedArray()
        )
    }
}

fun TriplesAsserter.validateCreatedOrgTriples(
    createResponse: HttpResponse,
    orgId: String,
    orgName: String,
    extraPatterns: List<PairPattern> = listOf()
) {
    // org-specific validation
    validateOrgTriples(createResponse, orgId, orgName, extraPatterns)

    // auto policy
    matchOneSubjectTerseByPrefix("m-policy:AutoOrgOwner") {
        includes(
            RDF.type exactly MMS.Policy,
        )
    }

    // transaction
    validateTransaction(orgPath="/orgs/$orgId")
}

open class OrgAny: CommonSpec() {
    open val logger = LoggerFactory.getLogger(OrgAny::class.java)

    val basePathOrgs = "/orgs"

    val demoOrgId = "open-mbee"
    val demoOrgName = "OpenMBEE"
    val demoOrgPath = "/orgs/$demoOrgId"

    val fooOrgId = "foo-org"
    val fooOrgName = "Foo Org"
    val fooOrgPath = "/orgs/$fooOrgId"

    val barOrgId = "bar-org"
    val barOrgName = "Bar Org"
    val barOrgPath = "/orgs/$barOrgId"

    val arbitraryPropertyIri = "https://demo.org/custom/prop"
    val arbitraryPropertyValue = "test"

    val validOrgBody = """
        <> dct:title "$demoOrgName"@en .
    """.trimIndent()
}

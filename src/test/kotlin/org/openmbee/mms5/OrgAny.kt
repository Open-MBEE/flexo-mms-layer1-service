package org.openmbee.mms5

import io.ktor.http.*
import io.ktor.server.testing.*
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.apache.jena.vocabulary.XSD
import org.openmbee.mms5.util.*
import org.slf4j.LoggerFactory

fun TriplesAsserter.validateOrgTriples(
    createResponse: TestApplicationResponse,
    orgId: String,
    orgName: String,
    extraPatterns: List<PairPattern> = listOf()
) {
    val orgPath = localIri("/orgs/$orgId")

    // org triples
    subject(orgPath) {
        exclusivelyHas(
            RDF.type exactly MMS.Org,
            MMS.id exactly orgId,
            DCTerms.title exactly orgName.en,
            MMS.etag exactly createResponse.headers[HttpHeaders.ETag]!!,
            *extraPatterns.toTypedArray()
        )
    }

    // auto policy
    matchOneSubjectTerseByPrefix("m-policy:AutoOrgOwner") {
        includes(
            RDF.type exactly MMS.Policy,
        )
    }

    // transaction
    subjectTerse("mt:") {
        includes(
            RDF.type exactly MMS.Transaction,
            MMS.created hasDatatype XSD.dateTime,
            MMS.org exactly orgPath.iri,
        )
    }

    // inspect
    subject("urn:mms:inspect") { ignoreAll() }
}

open class OrgAny: CommonSpec() {
    open val logger = LoggerFactory.getLogger(RepoUpdate::class.java)

    val orgId = "open-mbee"
    val orgName = "OpenMBEE"
    val orgPath = "/orgs/$orgId"

    val orgFooId = "foo-org"
    val orgFooName = "Foo Org"
    val orgFooPath = "/orgs/$orgFooId"

    val orgBarId = "bar-org"
    val orgBarName = "Bar Org"
    val orgBarPath = "/orgs/$orgBarId"

    val arbitraryPropertyIri = "https://demo.org/custom/prop"
    val arbitraryPropertyValue = "test"

    val validOrgBody = """
        <> dct:title "$orgName"@en .
    """.trimIndent()
}
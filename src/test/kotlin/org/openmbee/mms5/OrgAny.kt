package org.openmbee.mms5

import io.ktor.http.*
import io.ktor.server.testing.*
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.mms5.util.*

fun TriplesAsserter.validateOrgTriples(
    createResponse: TestApplicationResponse,
    orgId: String,
    orgName: String,
    extraPatterns: List<PairPattern> = listOf()
) {
    val orgPath = localIri("/orgs/$orgId")

    subject(orgPath) {
        exclusivelyHas(
            RDF.type exactly MMS.Org,
            MMS.id exactly orgId,
            DCTerms.title exactly orgName.en,
            MMS.etag exactly createResponse.headers[HttpHeaders.ETag]!!,
            *extraPatterns.toTypedArray()
        )
    }
}

open class OrgAny: CommonSpec() {
    val orgId = "open-mbee"
    val orgName = "OpenMBEE"
    val orgPath = "/orgs/$orgId"

    val orgFooId = "foo-org"
    val orgFooName = "Foo Org"
    val orgFooPath = "/orgs/$orgFooId"

    val orgBarId = "bar-org"
    val orgBarName = "Bar Org"
    val orgBarPath = "/orgs/$orgBarId"

    val validOrgBody = """
        <> dct:title "$orgName"@en .
    """.trimIndent()
}
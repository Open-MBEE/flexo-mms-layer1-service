package org.openmbee.flexo.mms

import io.ktor.http.*
import io.ktor.server.testing.*
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.util.*
import org.slf4j.LoggerFactory
import java.net.URLEncoder

fun TriplesAsserter.validateGroupTriples(
    createResponse: TestApplicationResponse,
    groupId: String,
    groupName: String,
    extraPatterns: List<PairPattern> = listOf()
) {
    val groupIri = localIri("/groups/${URLEncoder.encode(groupId, "UTF-8")}")

    // org triples
    subject(groupIri) {
        exclusivelyHas(
            RDF.type exactly MMS.Group,
            MMS.id exactly groupId,
            DCTerms.title exactly groupName.en,
            MMS.etag startsWith "",
            *extraPatterns.toTypedArray()
        )
    }
}

fun TriplesAsserter.validateCreatedGroupTriples(
    createResponse: TestApplicationResponse,
    groupId: String,
    groupName: String,
    extraPatterns: List<PairPattern> = listOf()
) {
    createResponse shouldHaveStatus HttpStatusCode.Created

    validateGroupTriples(createResponse, groupId, groupName, extraPatterns)

    // auto policy
    matchOneSubjectTerseByPrefix("m-policy:AutoGroupOwner") {
        includes(
            RDF.type exactly MMS.Policy,
        )
    }

    // transaction
    validateTransaction()

    // inspect
    subject(MMS_URNS.SUBJECT.inspect) { ignoreAll() }
}


open class GroupAny : CommonSpec() {
    open val logger = LoggerFactory.getLogger(GroupAny::class.java)

    val basePathGroups = "/groups"

    val demoGroupId = "ldap/cn=all.personnel,ou=personnel"
    val demoGroupPath = "/groups/${URLEncoder.encode(demoGroupId, "UTF-8")}"
    val demoGroupTitle = "Test Group"

//    val fooGroupId = ""

    val validGroupBody = withAllTestPrefixes("""
        <>
            dct:title "${demoGroupTitle}"@en ;
            .
    """.trimIndent())
}
package org.openmbee.flexo.mms

import org.slf4j.LoggerFactory
import org.openmbee.flexo.mms.util.*
import io.kotest.core.test.TestCase
import io.ktor.http.*
import io.ktor.server.testing.*
import org.apache.jena.rdf.model.ResourceFactory
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF

// used as data for the rest of the files
open class ScratchAny: RepoAny() {
    // build demo prefixes and queries
    override val logger = LoggerFactory.getLogger(ScratchAny::class.java)

    val basePathScratches = "$demoRepoPath/scratches"

    val demoScratchId = "new-scratch"
    val demoScratchName = "New Scratch"
    val demoScratchPath = "$basePathScratches/$demoScratchId"

    val validScratchBody = """
        <> dct:title "$demoScratchName"@en .
    """.trimIndent()

    val fooScratchId = "foo-scratch"
    val fooScratchName = "foo-scratch"
    val fooScratchPath = "$demoRepoPath/scratches/$fooScratchId"

    val barScratchId = "bar-scratch"
    val barScratchName = "bar-scratch"
    val barScratchPath = "$demoRepoPath/scratches/$barScratchId"

    var repoEtag = ""

    // create an org before each repo test
    override suspend fun beforeEach(testCase: TestCase) {
        super.beforeEach(testCase)

        // creates an empty repo
        repoEtag = createRepo(demoOrgPath, demoRepoId, demoRepoName).response.headers[HttpHeaders.ETag]!!

    }
}

// triples asserter function(s)
fun TriplesAsserter.validateScratchTriples(
    scratchId: String,
    repoId: String,
    orgId: String,
    scratchName: String,
    extraPatterns: List<PairPattern> = listOf()
) {
    val scratchIri = localIri("/orgs/$orgId/repos/$repoId/scratches/$scratchId")

    // org triples
    subject(scratchIri) {
        exclusivelyHas(
            RDF.type exactly MMS.Scratch,
            MMS.id exactly scratchId,
            DCTerms.title exactly scratchName.en,
            MMS.etag startsWith "",
            MMS.created startsWith "",
            MMS.createdBy exactly ResourceFactory.createResource(userIri("root")),
            *extraPatterns.toTypedArray()
        )
    }

}

fun TriplesAsserter.validateCreatedScratchTriples(
    createResponse: TestApplicationResponse,
    scratchId: String,
    repoId: String,
    orgId: String,
    scratchName: String,
    extraPatterns: List<PairPattern> = listOf()
) {
    validateScratchTriples(scratchId, repoId, orgId, scratchName, extraPatterns)

    // auto policy  //FIXME check this when debugging
    matchOneSubjectTerseByPrefix("m-policy:AutoScratchOwner") {
        includes(
            RDF.type exactly MMS.Policy,
        )
    }

    // transaction
    validateTransaction(
        orgPath="/orgs/$orgId",
        repoPath = "/orgs/$orgId/repos/$repoId",
        scratchPath = "/orgs/$orgId/repos/$repoId/scratches/$scratchId"
    )
}

package org.openmbee.flexo.mms

import io.kotest.core.test.TestCase
import io.ktor.server.testing.*
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.apache.jena.vocabulary.XSD
import org.openmbee.flexo.mms.util.*
import org.slf4j.LoggerFactory

fun TriplesAsserter.validateRepoTriples(
    repoId: String,
    repoName: String,
    orgPath: String,
    extraPatterns: List<PairPattern> = listOf()
) {
    val repoPath = "$orgPath/repos/$repoId"

    // repo triples
    subject(localIri(repoPath)) {
        exclusivelyHas(
            RDF.type exactly MMS.Repo,
            MMS.id exactly repoId,
            MMS.org exactly localIri(orgPath).iri,
            DCTerms.title exactly repoName.en,
            MMS.etag startsWith "",
            *extraPatterns.toTypedArray()
        )
    }

    // inspect
    subject("urn:mms:inspect") { ignoreAll() }
}

fun TriplesAsserter.validateRepoTriplesWithMasterBranch(
    repoId: String,
    repoName: String,
    orgPath: String,
    extraPatterns: List<PairPattern> = listOf()
) {
    validateRepoTriples(repoId, repoName, orgPath, extraPatterns)

    val repoPath = "$orgPath/repos/$repoId"

    // master branch triples
    subject(localIri("$repoPath/branches/master")) {
        includes(
            RDF.type exactly MMS.Branch,
            MMS.id exactly "master",
            DCTerms.title exactly "Master".en,
            MMS.etag startsWith "",
            MMS.commit startsWith "".iri,
        )
    }
}


fun TriplesAsserter.validateCreatedRepoTriples(
    createResponse: TestApplicationResponse,
    repoId: String,
    repoName: String,
    orgPath: String,
    extraPatterns: List<PairPattern> = listOf()
) {
    val repoPath = "$orgPath/repos/$repoId"

    validateRepoTriplesWithMasterBranch(repoId, repoName, orgPath, extraPatterns)

    // auto policy
    matchOneSubjectTerseByPrefix("m-policy:AutoRepoOwner.") {
        includes(
            RDF.type exactly MMS.Policy,
        )
    }

    // transaction
    validateTransaction(orgPath=orgPath, repoPath=repoPath)
}

open class RepoAny : OrgAny() {
    override val logger = LoggerFactory.getLogger(RepoUpdate::class.java)

    val repoId = "new-repo"
    val repoName = "New Repo"
    val repoPath = "$orgPath/repos/$repoId"
    val commitsPath = "$repoPath/commits"
    val validRepoBody = """
        <> dct:title "$repoName"@en .
    """.trimIndent()

    val repoFooId = "foo-repo"
    val repoFooName = "foo-repo"
    val repoFooPath = "$orgPath/repos/$repoFooId"

    val repoBarId = "bar-repo"
    val repoBarName = "bar-repo"
    val repoBarPath = "$orgPath/repos/$repoBarId"

    // create an org before each repo test
    override suspend fun beforeEach(testCase: TestCase) {
        super.beforeEach(testCase)

        // create base org for repo test
        createOrg(orgId, orgName)
    }
}

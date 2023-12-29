package org.openmbee.flexo.mms

import io.kotest.core.test.TestCase
import io.ktor.server.testing.*
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
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
    optionalSubject(MMS_URNS.SUBJECT.inspect) { ignoreAll() }

    // aggregator
    optionalSubject(MMS_URNS.SUBJECT.aggregator) { ignoreAll() }
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

    // snapshots staging & model, commit, commit data, and lock
    matchMultipleSubjectsByPrefix(localIri("${repoPath}/snapshots/")) { ignoreAll() }
    matchMultipleSubjectsByPrefix(localIri("${repoPath}/commits/")) { ignoreAll() }
    matchOneSubjectByPrefix(localIri("${repoPath}/locks/")) { ignoreAll() }

    // transaction
    validateTransaction(orgPath=orgPath, repoPath=repoPath)
}

open class RepoAny : OrgAny() {
    override val logger = LoggerFactory.getLogger(RepoUpdate::class.java)

    val basePathRepos = "$demoOrgPath/repos"

    val demoRepoId = "new-repo"
    val demoRepoName = "New Repo"
    val demoRepoPath = "$basePathRepos/$demoRepoId"
    val demoCommitsPath = "$demoRepoPath/commits"
    val validRepoBody = """
        <> dct:title "$demoRepoName"@en .
    """.trimIndent()

    val fooRepoId = "foo-repo"
    val fooRepoName = "foo-repo"
    val fooRepoPath = "$demoOrgPath/repos/$fooRepoId"

    val barRepoId = "bar-repo"
    val barRepoName = "bar-repo"
    val barRepoPath = "$demoOrgPath/repos/$barRepoId"

    // create an org before each repo test
    override suspend fun beforeEach(testCase: TestCase) {
        super.beforeEach(testCase)

        // create base org for repo test
        createOrg(demoOrgId, demoOrgName)
    }
}

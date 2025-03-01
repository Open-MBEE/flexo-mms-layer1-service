package org.openmbee.flexo.mms

import io.kotest.core.test.TestCase
import io.ktor.http.*
import io.ktor.server.testing.*
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.util.*
import org.slf4j.LoggerFactory

// validates response triples for a repo
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

    // inspections
    optionalSubject(MMS_URNS.SUBJECT.inspect) { ignoreAll() }

    // context
    optionalSubject(MMS_URNS.SUBJECT.context) {
        // remove linked policy triples
        subject.listProperties(MMS.appliedPolicy).forEach {
            optionalSubject(it.`object`.asResource().uri) { ignoreAll() }
        }
        ignoreAll()
    }
}

// validates response triples for a repo and its master branch
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

// validates response triples for a newly created repo
fun TriplesAsserter.validateCreatedRepoTriples(
    createResponse: TestApplicationResponse,
    repoId: String,
    repoName: String,
    orgPath: String,
    extraPatterns: List<PairPattern> = listOf()
) {
    val repoPath = "$orgPath/repos/$repoId"

    // repo-specific validation
    validateRepoTriplesWithMasterBranch(repoId, repoName, orgPath, extraPatterns)

    // auto policy
    matchOneSubjectTerseByPrefix("m-policy:AutoRepoOwner.") {
        includes(
            RDF.type exactly MMS.Policy,
        )
    }

    // auto policy
    matchOneSubjectTerseByPrefix("m-policy:AutoBranchOwner.") {
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
    override val logger = LoggerFactory.getLogger(RepoAny::class.java)

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

        // create base orgs for repo test
        createOrg(demoOrgId, demoOrgName)
        createOrg(fooOrgId, fooOrgName)
        createOrg(barOrgId, barOrgName)
    }
}

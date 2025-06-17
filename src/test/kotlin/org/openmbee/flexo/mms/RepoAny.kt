package org.openmbee.flexo.mms

import io.kotest.core.test.TestCase
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.apache.jena.rdf.model.ResourceFactory
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
            MMS.created startsWith "",
            MMS.createdBy exactly ResourceFactory.createResource(userIri("root")),
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
    createResponse: HttpResponse,
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
        testApplication {
            // create base orgs for repo test
            createOrg(demoOrgId, demoOrgName)
            createOrg(fooOrgId, fooOrgName)
            createOrg(barOrgId, barOrgName)
        }
    }

    //
    // sparql/rdf test data for loading/updating graphs
    val demoPrefixes = PrefixMapBuilder().apply {
        add(
            "" to "https://mms.openmbee.org/demos/people/",
            "foaf" to "http://xmlns.com/foaf/0.1/",
        )
    }

    val demoPrefixesStr = demoPrefixes.toString()

    val insertAliceRex = """
        $demoPrefixesStr

        insert data {
            :Alice a :Person ;
                foaf:name "Alice" ;
                .

            :Rex a :Dog ;
                :owner :Alice ;
                :likes :PeanutButter ;
                foaf:name "Rex" ;
                .
        }
    """.trimIndent()

    val insertBobFluffy = """
        $demoPrefixesStr

        insert data {
            :Bob a :Person ;
                foaf:name "Bob" ;
                .

            :Fluffy a :Cat ;
                :owner :Bob ;
                :likes :Jelly ;
                foaf:name "Fluffy" ;
                .
        }
    """.trimIndent()

    val queryNames = """
        $demoPrefixesStr

        select ?name where {
            ?s a :Person .
            ?s foaf:name ?name .
        } order by asc(?name)
    """.trimIndent()

    val queryNamesAliceResult = """
        {
            "head": {
                "vars": [
                    "name"
                ]
            },
            "results": {
                "bindings": [
                    {
                        "name": {
                            "type": "literal",
                            "value": "Alice"
                        }
                    }
                ]
            }
        }
    """.trimIndent()

    val queryNamesAliceBobResult = """
        {
            "head": {
                "vars": [
                    "name"
                ]
            },
            "results": {
                "bindings": [
                    {
                        "name": {
                            "type": "literal",
                            "value": "Alice"
                        }
                    },
                    {
                        "name": {
                            "type": "literal",
                            "value": "Bob"
                        }
                    }
                ]
            }
        }
    """.trimIndent()

    val loadAliceRex = """
        $demoPrefixesStr

        :Alice a :Person ;
            foaf:name "Alice" .
        :Rex a :Dog ;
            :owner :Alice ;
            :likes :PeanutButter ;
            foaf:name "Rex" .
    """.trimIndent()

    val loadBobFluffy = """
        $demoPrefixesStr

        :Bob a :Person ;
            foaf:name "Bob" .
        :Fluffy a :Cat ;
            :owner :Bob ;
            :likes :Jelly ;
            foaf:name "Fluffy" .
    """.trimIndent()
}

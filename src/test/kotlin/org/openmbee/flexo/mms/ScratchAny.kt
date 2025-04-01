package org.openmbee.flexo.mms

import org.slf4j.LoggerFactory
import org.openmbee.flexo.mms.util.*
import io.kotest.core.test.TestCase
import io.ktor.server.testing.*
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF

// used as data for the rest of the files
open class ScratchAny: RefAny() {
    // build demo prefixes and queries
    override val logger = LoggerFactory.getLogger(RepoAny::class.java)

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
    val barScratchPath = "$demoRepoPath/repos/$barScratchId"

    // create an org before each repo test
    override suspend fun beforeEach(testCase: TestCase) {
        super.beforeEach(testCase)

        // creates an empty scratch (demoScratchPath includes demoScratchId)
        createScratch(demoScratchPath, demoScratchName)
    }

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
            // Added these to check org and repo
            MMS.org exactly localIri("/orgs/$orgId").iri,
            MMS.repo exactly localIri("/orgs/$orgId/repos/$repoId").iri,
            DCTerms.title exactly scratchName.en,
            // MMS.etag exactly createResponse.headers[HttpHeaders.ETag]!!,
            MMS.etag startsWith "",
            *extraPatterns.toTypedArray()
        )
    }

    // FIXME add the inspect and aggregator lines 33-36 of RepoAny.kt? Whatever it takes to make it work

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

    // inspect  // FIXME why did you put this here
    subject(MMS_URNS.SUBJECT.inspect) { ignoreAll() }
}

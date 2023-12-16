package org.openmbee.flexo.mms

import org.apache.jena.vocabulary.RDF
import org.apache.jena.vocabulary.XSD
import org.openmbee.flexo.mms.util.*

open class ModelAny: RefAny() {
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

    fun TriplesAsserter.validateModelCommitResponse(
        branchPath: String,
        etag: String,
    ) {
        matchOneSubjectByPrefix(localIri("$demoCommitsPath/")) {
            includes(
                RDF.type exactly MMS.Commit,
                MMS.etag exactly etag,
                MMS.submitted hasDatatype XSD.dateTime,
                MMS.parent startsWith localIri("$demoCommitsPath/").iri,
                MMS.data startsWith localIri("$demoCommitsPath/").iri,
                MMS.createdBy exactly localIri("/users/root").iri
            )
        }
        /*
        //currently it returns AutoOrgOwner,
        matchOneSubjectTerseByPrefix("m-policy:AutoOrgOwner") {
            includes(
                RDF.type exactly MMS.Policy,
            )
        }*/

        // validate transaction
        validateTransaction(orgPath=demoOrgPath, repoPath=demoRepoPath, branchPath=branchPath, user="root")

        // inspect
        subject("urn:mms:inspect") { ignoreAll() }
    }
}

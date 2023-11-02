package org.openmbee.mms5

import io.kotest.assertions.json.shouldBeJsonObject
import io.kotest.assertions.json.shouldEqualJson
import io.kotest.matchers.string.shouldStartWith
import io.ktor.http.*
import io.ktor.server.testing.*
import org.apache.jena.vocabulary.RDF
import org.apache.jena.vocabulary.XSD
import org.openmbee.mms5.util.*

open class ModelAny: RefAny() {
    val insertAliceRex = """
        prefix : <https://mms.openmbee.org/demos/people/>
        prefix foaf: <http://xmlns.com/foaf/0.1/>
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
        prefix : <https://mms.openmbee.org/demos/people/>
        prefix foaf: <http://xmlns.com/foaf/0.1/>
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

    val sparqlQueryAll = """
        select * where {
            ?s ?p ?o
        }
    """.trimIndent()

    val queryNames = """
        prefix : <https://mms.openmbee.org/demos/people/>
        prefix foaf: <http://xmlns.com/foaf/0.1/>
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

    val queryNamesBobResult = """
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
                            "value": "Bob"
                        }
                    }
                ]
            }
        }
    """.trimIndent()

    val loadAliceRex = """
        @prefix : <https://mms.openmbee.org/demos/people/>
        @prefix foaf: <http://xmlns.com/foaf/0.1/>

        :Alice a :Person ;
            foaf:name "Alice" .
        :Rex a :Dog ;
            :owner :Alice ;
            :likes :PeanutButter ;
            foaf:name "Rex" .
    """.trimIndent()

    val loadBobFluffy = """
        @prefix : <https://mms.openmbee.org/demos/people/>
        @prefix foaf: <http://xmlns.com/foaf/0.1/>

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
        matchOneSubjectByPrefix(localIri("$commitsPath/")) {
            includes(
                RDF.type exactly MMS.Commit,
                MMS.etag exactly etag,
                MMS.submitted hasDatatype XSD.dateTime,
                MMS.parent startsWith localIri("$commitsPath/").iri,
                MMS.data startsWith localIri("$commitsPath/").iri,
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
        validateTransaction(orgPath=orgPath, repoPath=repoPath, branchPath=branchPath, user="root")

        // inspect
        subject("urn:mms:inspect") { ignoreAll() }
    }
}

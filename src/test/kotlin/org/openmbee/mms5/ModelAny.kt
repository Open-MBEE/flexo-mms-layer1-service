package org.openmbee.mms5

import io.kotest.assertions.json.shouldBeJsonObject
import io.kotest.assertions.json.shouldEqualJson
import io.kotest.assertions.ktor.shouldHaveHeader
import io.kotest.assertions.ktor.shouldHaveStatus
import io.ktor.http.*
import io.ktor.server.testing.*
import org.apache.jena.vocabulary.RDF
import org.apache.jena.vocabulary.XSD
import org.openmbee.mms5.util.*

open class ModelAny: RefAny() {
    val sparqlUpdate = """
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

    val sparqlUpdate2 = """
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

    val sparqlQueryNames = """
        prefix : <https://mms.openmbee.org/demos/people/>
        prefix foaf: <http://xmlns.com/foaf/0.1/>
        select ?name where {
            ?s a :Person .
            ?s foaf:name ?name .
        } order by asc(?name)
    """.trimIndent()

    val sparqlQueryNamesResult = """
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
    val sparqlQueryNamesResult2 = """
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

    val sparqlQueryNamesResultBob = """
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
    val loadTurtle = """
        @prefix : <https://mms.openmbee.org/demos/people/>
        @prefix foaf: <http://xmlns.com/foaf/0.1/>

        :Alice a :Person ;
            foaf:name "Alice" .
        :Rex a :Dog ;
            :owner :Alice ;
            :likes :PeanutButter ;
            foaf:name "Rex" .
    """.trimIndent()

    val loadTurtle2 = """
        @prefix : <https://mms.openmbee.org/demos/people/>
        @prefix foaf: <http://xmlns.com/foaf/0.1/>

        :Bob a :Person ;
            foaf:name "Bob" .
        :Fluffy a :Cat ;
            :owner :Bob ;
            :likes :Jelly ;
            foaf:name "Fluffy" .
    """.trimIndent()

    fun TestApplicationCall.validateModelQueryResponse(
        expectedJson: String
    ) {
        response shouldHaveStatus HttpStatusCode.OK
        response.shouldHaveHeader("Content-Type", "application/sparql-results+json")
        response.content!!.shouldBeJsonObject()
        response.content!!.shouldEqualJson(expectedJson)
    }

    fun TriplesAsserter.validateModelCommitResponse(
        branchPath: String,
        etag: String,
        parentCommit: String
    ) {
        matchOneSubjectByPrefix(localIri("$commitsPath/")) {
            includes(
                RDF.type exactly MMS.Commit,
                MMS.etag exactly etag!!,
                MMS.submitted hasDatatype XSD.dateTime,
                MMS.parent exactly localIri("$commitsPath/$parentCommit").iri,
                MMS.data startsWith localIri("$commitsPath/")
            )
        }
        /*
        //currently it returns AutoOrgOwner,
        matchOneSubjectTerseByPrefix("m-policy:AutoOrgOwner") {
            includes(
                RDF.type exactly MMS.Policy,
            )
        }*/
        subjectTerse("mt:") {
            includes(
                RDF.type exactly MMS.Transaction,
                MMS.created hasDatatype XSD.dateTime,
                MMS.org exactly orgPath.iri,
                MMS.repo exactly repoPath.iri,
                MMS.branch exactly branchPath.iri,
                MMS.user exactly userIri("root").iri
            )
        }

        // inspect
        subject("urn:mms:inspect") { ignoreAll() }
    }
}

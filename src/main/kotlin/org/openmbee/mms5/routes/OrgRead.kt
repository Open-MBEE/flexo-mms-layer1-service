package org.openmbee.mms5.routes

import io.ktor.server.application.*
import io.ktor.http.HttpHeaders.ETag
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.apache.jena.rdf.model.Resource
import org.apache.jena.vocabulary.RDF
import org.openmbee.mms5.*
import java.net.http.HttpHeaders

private val SPARQL_BGP_ORG = """
    graph m-graph:Cluster {
        ?_org a mms:Org ;
            mms:etag ?__mms_etag ;
            ?org_p ?org_o .
        
        optional {
            ?thing mms:org ?_org ;
                ?thing_p ?thing_o .
        }
    }
    
    ${permittedActionSparqlBgp(Permission.READ_ORG, Scope.CLUSTER)}
"""

private val SPARQL_SELECT_ORG = """
    select distinct ?__mms_etag {
        $SPARQL_BGP_ORG
    } order by asc(?__mms_etag)
"""

private val SPARQL_CONSTRUCT_ORG = """
    construct {
        ?_org ?org_p ?org_o ;
            mms:etag ?__mms_etag .
        
        ?thing ?thing_p ?thing_o .
        
        ?_context a mms:Context ;
            mms:permit mms-object:Permission.ReadOrg ;
            mms:policy ?policy ;
            .
        
        ?__mms_policy ?__mms_policy_p ?__mms_policy_o .
        
        ?orgPolicy ?orgPolicy_p ?orgPolicy_o .
    } where {
        $SPARQL_BGP_ORG
        
        graph m-graph:AccessControl.Policies {
            ?__mms_policy ?__mms_policy_p ?__mms_policy_o .

            optional {
                ?orgPolicy a mms:Policy ;
                    mms:scope ?_org ;
                    ?orgPolicy_p ?orgPolicy_o .
            }
        }
    }
"""

fun Route.readOrg() {
    route("/orgs/{orgId?}") {
        head {
            call.mmsL1(Permission.READ_ORG) {
                // parse path params
                pathParams {
                    org()
                }

                // cache whether this request is asking for all orgs
                val allOrgs = orgId?.isBlank() ?: true

                // use quicker select query to fetch etags
                val selectResponseText = executeSparqlSelectOrAsk(SPARQL_SELECT_ORG) {
                    prefixes(prefixes)

                    // get by orgId
                    if(!allOrgs) {
                        iri(
                            "_org" to prefixes["mo"]!!,
                        )
                    }

                    iri(
                        "_context" to "urn:mms:context:$transactionId",
                    )
                }

                // parse the results
                val results = Json.parseToJsonElement(selectResponseText).jsonObject

                // hash all the org etags
                handleEtagAndPreconditions(results)

                // respond
                call.respondText("")
            }
        }

        get {
            call.mmsL1(Permission.READ_ORG) {
                // parse path params
                pathParams {
                    org()
                }

                // cache whether this request is asking for all orgs
                val allOrgs = orgId?.isBlank() ?: true

                // fetch all org details
                val constructResponseText = executeSparqlConstructOrDescribe(SPARQL_CONSTRUCT_ORG) {
                    prefixes(prefixes)

                    // get by orgId
                    if(!allOrgs) {
                        iri(
                            "_org" to prefixes["mo"]!!,
                        )
                    }

                    iri(
                        "_context" to "urn:mms:context:$transactionId",
                    )
                }

                // parse the response
                parseConstructResponse(constructResponseText) {
                    // hash all the org etags
                    if(allOrgs) {
                        handleEtagAndPreconditions(model, MMS.Org)
                    }
                    // just the individual org
                    else {
                        handleEtagAndPreconditions(model, prefixes["mo"])
                    }
                }

                // respond
                call.respondText(constructResponseText, contentType = RdfContentTypes.Turtle)
            }

        }
    }
}
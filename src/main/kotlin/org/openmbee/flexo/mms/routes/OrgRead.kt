package org.openmbee.flexo.mms.routes

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.*

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
        
        #?__mms_policy ?__mms_policy_p ?__mms_policy_o .
        
        ?orgPolicy ?orgPolicy_p ?orgPolicy_o .
    } where {
        $SPARQL_BGP_ORG
        
        graph m-graph:AccessControl.Policies {
            #?__mms_policy ?__mms_policy_p ?__mms_policy_o .

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
                val orgIri = if(allOrgs) null else prefixes["mo"]!!

                // use quicker select query to fetch etags
                val selectResponseText = executeSparqlSelectOrAsk(SPARQL_SELECT_ORG) {
                    acceptReplicaLag = true

                    prefixes(prefixes)

                    // get by orgId
                    orgIri?.let {
                        iri(
                            "_org" to it,
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
                val orgIri = if(allOrgs) null else prefixes["mo"]!!

                // fetch all org details
                val constructResponseText = executeSparqlConstructOrDescribe(SPARQL_CONSTRUCT_ORG) {
                    acceptReplicaLag = true

                    prefixes(prefixes)

                    // get by orgId
                    orgIri?.let {
                        iri(
                            "_org" to it,
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
                        handleEtagAndPreconditions(model, orgIri)
                    }
                }

                // respond
                call.respondText(constructResponseText, contentType = RdfContentTypes.Turtle)
            }

        }
    }
}
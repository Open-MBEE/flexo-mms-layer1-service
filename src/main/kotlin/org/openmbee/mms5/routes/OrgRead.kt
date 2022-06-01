package org.openmbee.mms5.routes

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.openmbee.mms5.*

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
    select ?__mms_etag {
        $SPARQL_BGP_ORG
    } order by asc(?__mms_etag)
"""

private val SPARQL_CONSTRUCT_ORG = """
    construct {
        ?_org ?org_p ?org_o ;
            mms:etag ?__mms_etag .
        
        ?thing ?thing_p ?thing_o .
        
        ?context a mms:Context ;
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
        
        bind(bnode() as ?context)
    }
"""

fun Route.readOrg() {
    route("/orgs/{orgId?}") {
        head {
            call.mmsL1(Permission.READ_ORG) {
                pathParams {
                    org()
                }

                val selectResponseText = executeSparqlSelectOrAsk(SPARQL_SELECT_ORG) {
                    prefixes(prefixes)

                    // get by orgId
                    if(false == orgId?.isBlank()) {
                        iri(
                            "_org" to prefixes["mo"]!!,
                        )
                    }
                }

                val results = Json.parseToJsonElement(selectResponseText).jsonObject

                handleEtagAndPreconditions(results)

                call.respondText("")
            }
        }

        get {
            call.mmsL1(Permission.READ_ORG) {
                pathParams {
                    org()
                }

                val constructResponseText = executeSparqlConstructOrDescribe(SPARQL_CONSTRUCT_ORG) {
                    prefixes(prefixes)

                    // get by orgId
                    if(false == orgId?.isBlank()) {
                        iri(
                            "_org" to prefixes["mo"]!!,
                        )
                    }
                }

                val model = KModel(prefixes) {
                    parseTurtle(
                        body = constructResponseText,
                        model = this,
                    )
                }

                handleEtagAndPreconditions(model, prefixes["mo"]!!)

                call.respondText(constructResponseText, contentType = RdfContentTypes.Turtle)
            }

        }
    }
}
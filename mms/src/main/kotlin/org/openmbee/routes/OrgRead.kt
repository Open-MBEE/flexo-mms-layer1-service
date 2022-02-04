package org.openmbee.routes

import io.ktor.application.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openmbee.*

private val SPARQL_BGP_ORG = """
    graph m-graph:Cluster {
        ?_org a mms:Org ;
            ?org_p ?org_o .
        
        optional {
            ?thing mms:org ?_org ;
                ?thing_p ?thing_o .
        }
    }
    
    ${permittedActionSparqlBgp(Permission.READ_ORG, Scope.CLUSTER)}
"""

private val SPARQL_SELECT_ORG = """
    select ?etag {
        $SPARQL_BGP_ORG
    } order by asc(?etag)
"""

private val SPARQL_CONSTRUCT_ORG = """
    construct {
        ?_org ?org_p ?org_o .
        
        ?thing ?thing_p ?thing_o .
        
        ?context a mms:Context ;
            mms:permit mms-object:Permission.ReadOrg ;
            mms:policy ?policy ;
            .
        
        ?policy ?policy_p ?policy_o .
        
        ?orgPolicy ?orgPolicy_p ?orgPolicy_o .
        
        <mms://inspect> <mms://etag> ?etag .
    } where {
        $SPARQL_BGP_ORG
        
        graph m-graph:AccessControl.Policies {
            ?policy ?policy_p ?policy_o .

            optional {
                ?orgPolicy a mms:Policy ;
                    mms:scope ?_org ;
                    ?orgPolicy_p ?orgPolicy_o .
            }
        }
        
        bind(bnode() as ?context)
    }
"""

fun Application.readOrg() {
    routing {
        route("/orgs/{orgId?}") {
            head {
                call.mmsL1(Permission.READ_ORG) {
                    pathParams {
                        org()
                    }

                    val selectResponseText = executeSparqlSelectOrAsk(SPARQL_SELECT_ORG) {
                        // get by orgId
                        if(false == orgId?.isBlank()) {
                            iri(
                                "_org" to prefixes["mo"]!!,
                            )
                        }
                    }

                    val results = Json.parseToJsonElement(selectResponseText).jsonObject

                    checkPreconditions(results)

                    call.respondText("", status = HttpStatusCode.OK)
                }
            }

            get {
                call.mmsL1(Permission.READ_ORG) {
                    pathParams {
                        org()
                    }

                    val constructResponseText = executeSparqlConstructOrDescribe(SPARQL_CONSTRUCT_ORG) {
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

                    checkPreconditions(model, prefixes["mo"]!!)

                    call.respondText(constructResponseText, contentType = RdfContentTypes.Turtle)
                }

            }
        }
    }
}
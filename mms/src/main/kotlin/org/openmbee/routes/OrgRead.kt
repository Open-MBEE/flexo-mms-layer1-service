package org.openmbee.routes

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import org.openmbee.*


private val SPARQL_QUERY_ORG = """
    construct {
        ?_org ?org_p ?org_o .
        
        ?thing ?thing_p ?thing_o .
        
        ?context a mms:Context ;
            mms:permit mms-object:Permission.ReadOrg ;
            mms:policy ?policy ;
            .
        
        ?policy ?policy_p ?policy_o .
        
        ?orgPolicy ?orgPolicy_p ?orgPolicy_o .
    } where {
        graph m-graph:Cluster {
            ?_org a mms:Org ;
                ?org_p ?org_o .
            
            optional {
                ?thing mms:org ?_org ;
                    ?thing_p ?thing_o .
            }
        }
        
        ${permittedActionSparqlBgp(Permission.READ_ORG, Scope.CLUSTER)}
        
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

@OptIn(InternalAPI::class)
fun Application.readOrg() {
    routing {
        get("/orgs/{orgId?}") {
            val orgId = call.parameters["orgId"]
            val userId = call.mmsUserId

            // missing userId
            if(userId.isEmpty()) {
                call.respondText("Missing header: `MMS5-User`")
                return@get
            }

            var constructQuery: String

            // get by orgId
            if(false == orgId?.isNullOrBlank()) {
                val prefixes = prefixesFor(userId=userId, orgId=orgId)

                constructQuery = prefixes.toString() + parameterizedSparql(SPARQL_QUERY_ORG) {
                    iri(
                        "_org" to prefixes["mo"]!!,
                    )
                }
            }
            // get all orgs
            else {
                val prefixes = prefixesFor(userId=userId)

                constructQuery = prefixes.toString() + parameterizedSparql(SPARQL_QUERY_ORG) {
                    this
                }
            }


            val selectResponseText = call.submitSparqlConstructOrDescribe(constructQuery)

            call.respondText(selectResponseText, contentType=RdfContentTypes.Turtle)
        }
    }
}
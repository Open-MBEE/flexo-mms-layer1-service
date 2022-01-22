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
            val context = call.normalize {
                user()
                org()
            }

            val prefixes = context.prefixes

            val parameterizer = Parameterizer(SPARQL_QUERY_ORG).apply {
                prefixes(prefixes)
            }

            // get by orgId
            if(false == context.orgId?.isBlank()) {
                parameterizer.iri(
                    "_org" to prefixes["mo"]!!,
                )
            }

            val constructQuery = parameterizer.toString()

            val selectResponseText = call.submitSparqlConstructOrDescribe(constructQuery)

            call.respondText(selectResponseText, contentType=RdfContentTypes.Turtle)
        }
    }
}
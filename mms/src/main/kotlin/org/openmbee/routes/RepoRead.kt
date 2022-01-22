package org.openmbee.routes

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import org.openmbee.RdfContentTypes
import org.openmbee.normalize
import org.openmbee.parameterizedSparql
import org.openmbee.submitSparqlConstructOrDescribe


private const val SPARQL_QUERY_REPO = """
    construct {
        ?_repo ?repo_p ?repo_o .
        
        ?thing ?thing_p ?thing_o .
        
        ?m_s ?m_p ?m_o .
    } where {
        graph m-graph:Cluster {
            ?_repo a mms:Repo ;
                mms:org ?_org ;
                ?repo_p ?repo_o .
            
            optional {
                ?thing mms:repo ?_repo ; 
                    ?thing_p ?thing_o .
            }
        }
        
        graph mor-graph:Metadata {
            ?m_s ?m_p ?m_o .
        }
    }
"""

@OptIn(InternalAPI::class)
fun Application.readRepo() {
    routing {
        get("/orgs/{orgId}/repos/{repoId?}") {
            val context = call.normalize {
                user()
                org()
                repo()
            }

            // ref prefixes
            val prefixes = context.prefixes

            // construct query string
            var constructQuery: String

            // get repo by orgId and repoId
            if(false == context.repoId?.isBlank()) {
                constructQuery = parameterizedSparql(SPARQL_QUERY_REPO) {
                    prefixes(prefixes)

                    iri(
                        "_org" to prefixes["mo"]!!,
                        "_repo" to prefixes["mor"]!!,
                    )
                }
            }
            // get all repos by orgId
            else {
                constructQuery = parameterizedSparql(SPARQL_QUERY_REPO) {
                    prefixes(prefixes)

                    iri(
                        "_org" to prefixes["mo"]!!,
                    )
                }
            }

            val constructResponseText = call.submitSparqlConstructOrDescribe(constructQuery)

            call.respondText(constructResponseText, contentType=RdfContentTypes.Turtle)
        }
    }
}
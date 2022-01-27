package org.openmbee.routes

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import org.openmbee.*


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
            call.crud {
                pathParams {
                    org()
                    repo()
                }

                val parameterizer = Parameterizer(SPARQL_QUERY_REPO, prefixes).apply {
                    iri(
                        "_org" to prefixes["mo"]!!,
                    )
                }

                // get by orgId
                if(false == repoId?.isBlank()) {
                    parameterizer.apply{
                        iri(
                            "_repo" to prefixes["mor"]!!,
                        )
                    }
                }

                val constructString = parameterizer.toString()

                val constructResponseText = executeSparqlConstructOrDescribe(constructString)

                call.respondText(constructResponseText, contentType=RdfContentTypes.Turtle)
            }
        }
    }
}
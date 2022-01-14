package org.openmbee.routes

import io.ktor.application.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import org.openmbee.*
import org.openmbee.plugins.client


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
            val orgId = call.parameters["orgId"]
            val repoId = call.parameters["repoId"]

            val userId = call.request.headers["mms5-user"]?: ""

            // missing userId
            if(userId.isEmpty()) {
                call.respondText("Missing header: `MMS5-User`")
                return@get
            }

            // construct query string
            var constructQuery: String

            // get repo by orgId and repoId
            if(false == repoId?.isNullOrBlank()) {
                val prefixes = prefixesFor(orgId=orgId, repoId=repoId)

                constructQuery = parameterizedSparql(SPARQL_QUERY_REPO) {
                    prefixes(prefixes)

                    iri(
                        "_org" to prefixes["mo"]!!,
                        "_repo" to prefixes["mor"]!!,
                    )
                }.toString()
            }
            // get all repos by orgId
            else {
                val prefixes = prefixesFor(orgId=orgId)

                constructQuery = parameterizedSparql(SPARQL_QUERY_REPO) {
                    prefixes(prefixes)

                    iri(
                        "_org" to prefixes["mo"]!!,
                    )
                }.toString()
            }

            val selectResponse = call.submitSparqlConstruct(constructQuery)

            call.respondText(selectResponse.readText(), status=selectResponse.status, contentType=selectResponse.contentType())
        }
    }
}
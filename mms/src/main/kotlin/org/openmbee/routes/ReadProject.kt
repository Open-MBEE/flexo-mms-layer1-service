package org.openmbee.routes

import io.ktor.application.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import org.openmbee.*
import org.openmbee.plugins.client


private const val SPARQL_QUERY_PROJECT = """
    construct {
        ?_project ?p ?o .
    } where {
        graph m-graph:Cluster {
            ?_project a mms:Project ;
                ?p ?o .
        }
    }
"""

@OptIn(InternalAPI::class)
fun Application.readProject() {
    routing {
        get("/projects/{projectId?}") {
            val projectId = call.parameters["projectId"]

            var constructQuery: String

            // get by projectId
            if(false == projectId?.isNullOrBlank()) {
                val prefixes = prefixesFor(projectId=projectId)

                constructQuery = prefixes.toString() + parameterizedSparql(SPARQL_QUERY_PROJECT) {
                    iri(
                        "_org" to prefixes["mo"]!!,
                    )
                }
            }
            // get all projects
            else {
                val prefixes = prefixesFor()

                constructQuery = prefixes.toString() + parameterizedSparql(SPARQL_QUERY_PROJECT) {
                    this
                }
            }

            val selectResponse = client.submitSparqlConstruct(constructQuery)

            call.respondText(selectResponse.readText(), status=selectResponse.status, contentType=selectResponse.contentType())
        }
    }
}
package org.openmbee.routes

import io.ktor.application.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import org.openmbee.parameterizedSparql
import org.openmbee.plugins.client
import org.openmbee.prefixesFor
import org.openmbee.submitSparqlConstruct


private const val SPARQL_QUERY_ORG = """
    construct {
        ?_org ?p ?o .
    } where {
        graph m-graph:Cluster {
            ?_org a mms:Org ;
                ?p ?o .
        }
    }
"""

@OptIn(InternalAPI::class)
fun Application.readOrg() {
    routing {
        get("/orgs/{orgId?}") {
            val orgId = call.parameters["orgId"]

            var constructQuery: String

            // get by orgId
            if(false == orgId?.isNullOrBlank()) {
                val prefixes = prefixesFor(orgId=orgId)

                constructQuery = prefixes.toString() + parameterizedSparql(SPARQL_QUERY_ORG) {
                    iri(
                        "_org" to prefixes["mo"]!!,
                    )
                }
            }
            // get all orgs
            else {
                val prefixes = prefixesFor()

                constructQuery = prefixes.toString() + parameterizedSparql(SPARQL_QUERY_ORG) {
                    this
                }
            }


            val selectResponse = client.submitSparqlConstruct(constructQuery)

            call.respondText(selectResponse.readText(), status=selectResponse.status, contentType=selectResponse.contentType())
        }
    }
}
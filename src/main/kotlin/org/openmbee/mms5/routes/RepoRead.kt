package org.openmbee.mms5.routes

import io.ktor.application.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import kotlinx.serialization.json.*
import org.openmbee.mms5.*

private const val SPARQL_BGP_REPO = """
    graph m-graph:Cluster {
        ?_repo a mms:Repo ;
            mms:etag ?etagCluster ;
            mms:org ?_org ;
            ?repo_p ?repo_o .
        
        optional {
            ?thing mms:repo ?_repo ; 
                ?thing_p ?thing_o .
        }
        
        bind(iri(concat(str(?_repo), "/graphs/")) as ?metadataGraph)
    }
    
    graph ?metadataGraph {
        ?m_s ?m_p ?m_o ;
            mms:etag ?etagRepo .
    }
    
    bind(concat(?etagCluster, ?etagRepo) as ?__mms_etag)
"""

private const val SPARQL_SELECT_REPO = """
    select ?__mms_etag {
        $SPARQL_BGP_REPO
    } order by asc(?__mms_etag)
"""

private const val SPARQL_CONSTRUCT_REPO = """
    construct {
        ?_repo ?repo_p ?repo_o .
        
        ?thing ?thing_p ?thing_o .
        
        ?m_s ?m_p ?m_o .
        
        <urn:mms:inspect> <urn:mms:etag> ?__mms_etag .
    } where {
        $SPARQL_BGP_REPO
    }
"""


fun Route.readRepo() {
    route("/orgs/{orgId}/repos/{repoId?}") {
        head {
            call.mmsL1(Permission.READ_REPO) {
                pathParams {
                    org()
                    repo()
                }

                val selectResponseText = executeSparqlSelectOrAsk(SPARQL_SELECT_REPO) {
                    prefixes(prefixes)

                    iri(
                        "_org" to prefixes["mo"]!!,
                    )

                    // get by repoId
                    if(false == repoId?.isBlank()) {
                        iri(
                            "_repo" to prefixes["mor"]!!,
                        )
                    }
                }

                val results = Json.parseToJsonElement(selectResponseText).jsonObject

                checkPreconditions(results)

                call.respondText("", status = HttpStatusCode.OK)
            }
        }

        get {
            call.mmsL1(Permission.READ_REPO) {
                pathParams {
                    org()
                    repo()
                }

                val constructResponseText = executeSparqlConstructOrDescribe(SPARQL_CONSTRUCT_REPO) {
                    prefixes(prefixes)

                    iri(
                        "_org" to prefixes["mo"]!!,
                    )

                    // get by repoId
                    if(false == repoId?.isBlank()) {
                        iri(
                            "_repo" to prefixes["mor"]!!,
                        )
                    }
                }

                val model = KModel(prefixes) {
                    parseTurtle(
                        body = constructResponseText,
                        model = this,
                    )
                }

                checkPreconditions(model, prefixes["mor"])

                call.respondText(constructResponseText, contentType = RdfContentTypes.Turtle)
            }
        }
    }
}
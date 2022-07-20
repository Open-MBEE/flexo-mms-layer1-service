package org.openmbee.mms5.routes

import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
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
        
        bind(iri(concat(str(?_repo), "/graphs/Metadata")) as ?metadataGraph)
    }
    
    graph ?metadataGraph {
        ?m_s ?m_p ?m_o ;
            mms:etag ?etagRepo .
    }
    
    bind(concat(?etagCluster, ?etagRepo) as ?__mms_etag)
"""

private const val SPARQL_SELECT_REPO = """
    select distinct ?__mms_etag {
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
                // parse path params
                pathParams {
                    org()
                    repo()
                }

                // cache whether this request is asking for all repos
                val allRepos = repoId?.isBlank() ?: true

                // use quicker select query to fetch etags
                val selectResponseText = executeSparqlSelectOrAsk(SPARQL_SELECT_REPO) {
                    prefixes(prefixes)

                    // always belongs to some org
                    iri(
                        "_org" to prefixes["mo"]!!,
                    )

                    // get by repoId
                    if(!allRepos) {
                        iri(
                            "_repo" to prefixes["mor"]!!,
                        )
                    }
                }

                // parse the results
                val results = Json.parseToJsonElement(selectResponseText).jsonObject

                // hash all the repo etags
                handleEtagAndPreconditions(results)

                // respond
                call.respondText("", status = HttpStatusCode.OK)
            }
        }

        get {
            call.mmsL1(Permission.READ_REPO) {
                // parse path params
                pathParams {
                    org()
                    repo()
                }

                // cache whether this request is asking for all repos
                val allRepos = repoId?.isBlank() ?: true

                // fetch all repo details
                val constructResponseText = executeSparqlConstructOrDescribe(SPARQL_CONSTRUCT_REPO) {
                    prefixes(prefixes)

                    // always belongs to some org
                    iri(
                        "_org" to prefixes["mo"]!!,
                    )

                    // get by repoId
                    if(!allRepos) {
                        iri(
                            "_repo" to prefixes["mor"]!!,
                        )
                    }
                }

                // parse the response
                parseConstructResponse(constructResponseText) {
                    // hash all the repo etags
                    if(allRepos) {
                        handleEtagAndPreconditions(model, MMS.Repo)
                    }
                    // just the individual repo
                    else {
                        handleEtagAndPreconditions(model, prefixes["mor"])
                    }
                }

                // respond
                call.respondText(constructResponseText, contentType = RdfContentTypes.Turtle)
            }
        }
    }
}
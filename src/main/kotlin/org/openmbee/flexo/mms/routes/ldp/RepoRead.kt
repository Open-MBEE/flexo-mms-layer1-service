package org.openmbee.flexo.mms.routes.ldp

import io.ktor.http.*
import io.ktor.server.response.*
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.routes.SPARQL_VAR_NAME_ORG
import org.openmbee.flexo.mms.routes.SPARQL_VAR_NAME_REPO
import org.openmbee.flexo.mms.server.LdpDcLayer1Context
import org.openmbee.flexo.mms.server.LdpGetResponse
import org.openmbee.flexo.mms.server.LdpHeadResponse
import org.openmbee.flexo.mms.server.LdpReadResponse

// reusable basic graph pattern for matching repo(s)
private val SPARQL_BGP_REPO: (Boolean, Boolean) -> String = { allRepos, allData -> """
    graph m-graph:Cluster {
        ${"""
            optional {
                ?thing mms:repo ?$SPARQL_VAR_NAME_REPO ; 
                    ?thing_p ?thing_o ;
                    .
            }
        """.reindent(2) iff allData}

        ${"optional {" iff allRepos}${"""
            ?$SPARQL_VAR_NAME_REPO a mms:Repo ;
                mms:etag ?__mms_etag ;
                mms:org ?$SPARQL_VAR_NAME_ORG ;
                ${"?repo_p ?repo_o ;" iff allData}
                .
                
            bind(iri(concat(str(?$SPARQL_VAR_NAME_REPO), "/graphs/Metadata")) as ?metadataGraph)
        """.reindent(if(allRepos) 3 else 2)}
        ${"}" iff allRepos}
    }
        
    optional {
        graph ?metadataGraph {
            ?m_s mms:etag ?elementEtag ;
                ${"?m_p ?m_o ;" iff allData}
            .
        }
    }
    
    ${permittedActionSparqlBgp(Permission.READ_REPO, Scope.REPO,
        if(allRepos) "^mor:?$".toRegex() else null,
        if(allRepos) "" else null)}
""" }


// construct graph of all relevant repo metadata
private val SPARQL_CONSTRUCT_REPO: (Boolean, Boolean) -> String = { allRepos, allData -> """
    construct {
        ?$SPARQL_VAR_NAME_REPO a mms:Repo ;
            mms:etag ?__mms_etag ;
            ?repo_p ?repo_o ;
            .
        
        ?thing ?thing_p ?thing_o .
        
        ?m_s ?m_p ?m_o .
        
        ${generateReadContextBgp(Permission.READ_REPO).reindent(2)}
    } where {
        ${SPARQL_BGP_REPO(allRepos, allData).reindent(2)}
    }
""" }


/**
 * Fetches repo(s) metadata
 */
suspend fun LdpDcLayer1Context<LdpReadResponse>.fetchRepos(allRepos: Boolean=false, allData: Boolean=false): String {
    // cache whether this request is asking for all repos
    val repoIri = if(allRepos) null else prefixes["mor"]!!

    // fetch all repo details
    val constructResponseText = executeSparqlConstructOrDescribe(SPARQL_CONSTRUCT_REPO(allRepos, allData)) {
        acceptReplicaLag = true

        prefixes(prefixes)

        // always belongs to some org
        iri(
            SPARQL_VAR_NAME_ORG to prefixes["mo"]!!,
        )

        // get by repoId
        repoIri?.let {
            iri(
                SPARQL_VAR_NAME_REPO to it,
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
            handleEtagAndPreconditions(model, repoIri)
        }
    }

    return constructResponseText
}

/**
 * Fetches repo(s) metadata, ignoring unnecessary data to satisfy a HEAD request
 */
suspend fun LdpDcLayer1Context<LdpHeadResponse>.headRepos(allRepos: Boolean=false) {
    fetchRepos(allRepos, false)

    // respond
    call.respond(HttpStatusCode.NoContent)
}

/**
 * Fetches repo(s) metadata and all relevant data to satisfy a GET request
 */
suspend fun LdpDcLayer1Context<LdpGetResponse>.getRepos(allRepos: Boolean=false) {
    val constructResponseText = fetchRepos(allRepos, true)

    // respond
    call.respondText(constructResponseText, contentType = RdfContentTypes.Turtle)
}

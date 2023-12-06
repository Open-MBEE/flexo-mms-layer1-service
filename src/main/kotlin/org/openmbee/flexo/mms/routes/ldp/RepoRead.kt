package org.openmbee.flexo.mms.routes.ldp

import io.ktor.http.*
import io.ktor.server.response.*
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.server.LdpDcLayer1Context
import org.openmbee.flexo.mms.server.LdpGetResponse
import org.openmbee.flexo.mms.server.LdpHeadResponse
import org.openmbee.flexo.mms.routes.SPARQL_VAR_NAME_ORG
import org.openmbee.flexo.mms.routes.SPARQL_VAR_NAME_REPO


// reusable basic graph pattern for matching repo(s)
private val SPARQL_BGP_REPO: (Boolean) -> String = { allRepos -> """
    graph m-graph:Cluster {
        ?$SPARQL_VAR_NAME_REPO a mms:Repo ;
            mms:etag ?etagCluster ;
            mms:org ?$SPARQL_VAR_NAME_ORG ;
            ?repo_p ?repo_o .
        
        optional {
            ?thing mms:repo ?$SPARQL_VAR_NAME_REPO ; 
                ?thing_p ?thing_o .
        }
        
        bind(iri(concat(str(?$SPARQL_VAR_NAME_REPO), "/graphs/Metadata")) as ?metadataGraph)
    }
    
    graph ?metadataGraph {
        ?m_s ?m_p ?m_o ;
            mms:etag ?etagRepo .
    }
    
    bind(concat(?etagCluster, ?etagRepo) as ?__mms_etag)
    
    ${permittedActionSparqlBgp(Permission.READ_REPO, Scope.REPO,
        if(allRepos) "^mor:?$".toRegex() else null,
        if(allRepos) "" else null)}
""" }

// select ETag(s) of existing repo(s)
private val SPARQL_SELECT_REPO_ETAGS: (Boolean) -> String = { allRepos -> """
    select distinct ?__mms_etag {
        ${SPARQL_BGP_REPO(allRepos)}
    } order by asc(?__mms_etag)
""" }

// construct graph of all relevant repo metadata
private val SPARQL_CONSTRUCT_REPO: (Boolean) -> String = { allRepos -> """
    construct {
        ?$SPARQL_VAR_NAME_REPO ?repo_p ?repo_o .
        
        ?thing ?thing_p ?thing_o .
        
        ?m_s ?m_p ?m_o .
        
        <urn:mms:inspect> <urn:mms:etag> ?__mms_etag .
    } where {
        ${SPARQL_BGP_REPO(allRepos)}
    }
"""
}


/**
 * Fetches the repo(s) ETag
 */
suspend fun LdpDcLayer1Context<LdpHeadResponse>.headRepos(allRepos: Boolean=false) {
    // cache whether this request is asking for all repos
    val repoIri = if(allRepos) null else prefixes["mor"]!!

    // fetch all repos
    val selectResponseText = executeSparqlSelectOrAsk(SPARQL_SELECT_REPO_ETAGS(allRepos)) {
        acceptReplicaLag = true

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

    // parse the result bindings
    val bindings = parseSparqlResultsJson(selectResponseText)

    // hash all the repo etags
    handleEtagAndPreconditions(bindings)

    // respond
    call.respond(HttpStatusCode.NoContent)
}


/**
 * Fetches repo(s) metadata
 */
suspend fun LdpDcLayer1Context<LdpGetResponse>.getRepos(allRepos: Boolean=false) {
    // cache whether this request is asking for all repos
    val repoIri = if(allRepos) null else prefixes["mor"]!!

    // fetch all repo details
    val constructResponseText = executeSparqlConstructOrDescribe(SPARQL_CONSTRUCT_REPO(allRepos)) {
        acceptReplicaLag = true

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

    // respond
    call.respondText(constructResponseText, contentType = RdfContentTypes.Turtle)
}
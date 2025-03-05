package org.openmbee.flexo.mms.routes.ldp

import io.ktor.http.*
import io.ktor.server.response.*
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.routes.SPARQL_VAR_NAME_COMMIT
import org.openmbee.flexo.mms.server.LdpDcLayer1Context
import org.openmbee.flexo.mms.server.LdpGetResponse
import org.openmbee.flexo.mms.server.LdpHeadResponse
import org.openmbee.flexo.mms.server.LdpReadResponse

// reusable basic graph pattern for matching commit(s)
private val SPARQL_BGP_COMMIT: (Boolean, Boolean) -> String = { allCommits, allData -> """
    graph mor-graph:Metadata {
        ${"optional {" iff allCommits}${"""
            ?$SPARQL_VAR_NAME_COMMIT a mms:Commit ;
                mms:etag ?__mms_etag ;
                ${"?commit_p ?commit_o ;" iff allData}
                .
        """.reindent(if(allCommits) 3 else 2)}
        ${"}" iff allCommits}
        
        ${"""
            optional {
                ?thing mms:commit ?$SPARQL_VAR_NAME_COMMIT ;
                    ?thing_p ?thing_o ;
                    .
            }
        """.reindent(2) iff allData}
    }
    
    ${permittedActionSparqlBgp(Permission.READ_COMMIT, Scope.COMMIT,
    if(allCommits) "^morc:?$".toRegex() else null,
    if(allCommits) "" else null)}
"""}

// construct graph of all relevant commit metadata
private val SPARQL_CONSTRUCT_COMMIT: (Boolean, Boolean) -> String = { allCommits, allData -> """
    construct {
        ?$SPARQL_VAR_NAME_COMMIT a mms:Commit ;
            mms:etag ?__mms_etag ;
            ?commit_p ?commit_o ;
            .
        
        ?thing ?thing_p ?thing_o .
        
        ${generateReadContextBgp(Permission.READ_COMMIT).reindent(2)}
    } where {
        ${SPARQL_BGP_COMMIT(allCommits, allData).reindent(2)}
    }
""" }


/**
 * Fetches commit(s) metadata
 */
suspend fun LdpDcLayer1Context<LdpReadResponse>.fetchCommits(allCommits: Boolean=false, allData: Boolean=false): String {
    // cache whether this request is asking for all commits
    val commitIri = if(allCommits) null else prefixes["morc"]!!

    // fetch all commit details
    val constructResponseText = executeSparqlConstructOrDescribe(SPARQL_CONSTRUCT_COMMIT(allCommits, allData)) {
        acceptReplicaLag = true

        // internal query, give it all the prefixes
        prefixes(prefixes)

        // get by commitId
        commitIri?.let {
            iri(
                SPARQL_VAR_NAME_COMMIT to it,
            )
        }
    }

    // parse the response
    parseConstructResponse(constructResponseText) {
        // hash all the repo etags
        if(allCommits) {
            handleEtagAndPreconditions(model, MMS.Commit)
        }
        // just the individual repo
        else {
            handleEtagAndPreconditions(model, commitIri)
        }
    }

    return constructResponseText
}

/**
 * Fetches commit(s) metadata, ignoring unnecessary data to satisfy a HEAD request
 */
suspend fun LdpDcLayer1Context<LdpHeadResponse>.headCommits(allCommits: Boolean=false) {
    fetchCommits(allCommits, false)

    // respond
    call.respond(HttpStatusCode.NoContent)
}

/**
 * Fetches commit(s) metadata and all relevant data to satisfy a GET request
 */
suspend fun LdpDcLayer1Context<LdpGetResponse>.getCommits(allCommits: Boolean=false) {
    val constructResponseText = fetchCommits(allCommits, true)

    // respond
    call.respondText(constructResponseText, contentType = RdfContentTypes.Turtle)
}

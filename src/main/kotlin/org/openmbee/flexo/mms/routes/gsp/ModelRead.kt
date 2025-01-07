package org.openmbee.flexo.mms.routes.gsp

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.server.GspLayer1Context
import org.openmbee.flexo.mms.server.GspReadResponse

enum class RefType {
    BRANCH,
    LOCK,
    SCRATCH,
}

suspend fun GspLayer1Context<GspReadResponse>.readModel(refType: RefType) {
    parsePathParams {
        org()
        repo()
        when(refType) {
            RefType.BRANCH -> branch()
            RefType.LOCK -> lock()
            RefType.SCRATCH -> scratch()
        }
    }

    val authorizedIri = "<${MMS_URNS.SUBJECT.auth}:${transactionId}>"

    val constructString = buildSparqlQuery {
        construct {
            raw("""
                $authorizedIri <${MMS_URNS.PREDICATE.policy}> ?__mms_authMethod . 

                ?s ?p ?o
            """)
        }
        where {
            when(refType) {
                RefType.BRANCH -> auth(Permission.READ_BRANCH.scope.id, BRANCH_QUERY_CONDITIONS)
                RefType.LOCK -> auth(Permission.READ_LOCK.scope.id, LOCK_QUERY_CONDITIONS)
                RefType.SCRATCH -> auth(Permission.READ_REPO.scope.id, REPO_QUERY_CONDITIONS)
            }

            if(refType == RefType.SCRATCH) {
                graph("mor-graph:Scratch.$scratchId") {
                    raw("?s ?p ?o")
                }
            }
            else {
                raw("""
                    graph mor-graph:Metadata {
                        ${when(refType) {
                            RefType.BRANCH -> "morb:"
                            RefType.LOCK -> "morl:"
                            else -> "urn:mms:invalid"
                        }} mms:commit/^mms:commit ?ref .
                        
                        ?ref mms:snapshot ?modelSnapshot .
                        
                        ?modelSnapshot a mms:Model ;
                            mms:graph ?modelGraph .
                    }
    
                    graph ?modelGraph {
                        ?s ?p ?o .
                    }
                """)
            }
        }
    }

    val constructResponseText = executeSparqlConstructOrDescribe(constructString) {
        acceptReplicaLag = true

        prefixes(prefixes)
    }

    if(!constructResponseText.contains(authorizedIri)) {
        log("Rejecting unauthorized request with 404\n${constructResponseText}")

        if(call.application.glomarResponse) {
            throw Http404Exception(call.request.path())
        }
        else {
            throw Http403Exception(this, call.request.path())
        }
    }
    // HEAD method
    else if(call.request.httpMethod == HttpMethod.Head) {
        call.respond(HttpStatusCode.OK)
    }
    // GET
    else {
        // try to avoid parsing model for performance reasons
        val modelText = constructResponseText.replace("""$authorizedIri\s+<${MMS_URNS.PREDICATE.policy}>\s+"(user|group)"\s+\.""".toRegex(), "")

        call.respondText(modelText, contentType=RdfContentTypes.Turtle)
    }
}

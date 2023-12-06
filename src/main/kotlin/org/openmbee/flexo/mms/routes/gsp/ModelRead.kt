package org.openmbee.flexo.mms.routes.gsp

import io.ktor.server.request.*
import io.ktor.server.response.*
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.server.GspContext

suspend fun GspContext.readModel() {
    parsePathParams {
        org()
        repo()
        branch()
    }

    val authorizedIri = "<urn:mms:auth:${transactionId}>"

    val constructString = buildSparqlQuery {
        construct {
            raw("""
                $authorizedIri <urn:mms:policy> ?__mms_authMethod . 

                ?s ?p ?o
            """)
        }
        where {
            auth(Permission.READ_BRANCH.id, BRANCH_QUERY_CONDITIONS)

            raw("""
                graph mor-graph:Metadata {
                    morb: mms:commit/^mms:commit ?ref .
                    
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
    else {
        // try to avoid parsing model for performance reasons
        val modelText = constructResponseText.replace("""$authorizedIri\s+<urn:mms:policy>\s+\"(user|group)\"\s+\.""".toRegex(), "")

        call.respondText(modelText, contentType=RdfContentTypes.Turtle)
    }
}

package org.openmbee.mms5.routes.gsp

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.openmbee.mms5.*


fun Route.readModel() {
    get("/orgs/{orgId}/repos/{repoId}/branches/{branchId}/graph") {
        call.mmsL1(Permission.READ_BRANCH) {
            pathParams {
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
                    auth(permission.scope.id, BRANCH_QUERY_CONDITIONS)

                    raw("""
                        graph mor-graph:Metadata {
                            morb: mms:snapshot/mms:graph ?modelGraph .
                        }

                        graph ?modelGraph {
                            ?s ?p ?o .
                        }
                    """)
                }
            }

            val constructResponseText = executeSparqlConstructOrDescribe(constructString)

            if(!constructResponseText.contains(authorizedIri)) {
                throw Http404Exception()
            }
            else {
                // try to avoid parsing model for performance reasons
                val modelText = constructResponseText.replace("""$authorizedIri\s+<urn:mms:policy>\s+\"(user|group)\"\s+\.""".toRegex(), "")

                call.respondText(modelText, contentType=RdfContentTypes.Turtle)
            }

        }

    }
}

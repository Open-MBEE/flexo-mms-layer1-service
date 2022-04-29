package org.openmbee.mms5.routes.gsp

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
import org.openmbee.mms5.*


fun Route.readModel() {
    get("/orgs/{orgId}/repos/{repoId}/branches/{branchId}/graph") {
        call.mmsL1(Permission.READ_BRANCH) {
            pathParams {
                org()
                repo()
                branch()
            }

            val constructString = buildSparqlQuery {
                construct {
                    raw("""
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

            call.respondText(executeSparqlConstructOrDescribe(constructString), contentType=RdfContentTypes.Turtle)
        }

    }
}

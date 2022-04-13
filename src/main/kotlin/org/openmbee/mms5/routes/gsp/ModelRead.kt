package org.openmbee.mms5.routes.gsp

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
import org.openmbee.mms5.Permission
import org.openmbee.mms5.RdfContentTypes
import org.openmbee.mms5.mmsL1


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

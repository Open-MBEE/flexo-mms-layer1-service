package org.openmbee.flexo.mms.routes

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.openmbee.flexo.mms.*

private val DEFAULT_CONDITIONS =  LOCK_CRUD_CONDITIONS.append {
    permit(Permission.DELETE_LOCK, Scope.LOCK)
}

fun Route.deleteLock() {
    delete("/orgs/{orgId}/repos/{repoId}/locks/{lockId}") {
        call.mmsL1(Permission.DELETE_LOCK) {
            pathParams {
                org()
                repo()
                lock()
            }

            val localConditions = DEFAULT_CONDITIONS

            val updateString = buildSparqlUpdate {
                compose {
                    txn()
                    conditions(localConditions)

                    raw(dropLock())
                }
            }

            log.info(updateString)

            // fetch transaction
            val constructString = buildSparqlQuery {
                construct {
                    txn()
                }
                where {
                    txn()
                }
            }

            val constructResponseText = executeSparqlConstructOrDescribe(constructString)

            call.respondText(constructResponseText, contentType=RdfContentTypes.Turtle)
        }
    }
}

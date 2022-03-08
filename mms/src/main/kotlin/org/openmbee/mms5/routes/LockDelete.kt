package org.openmbee.mms5.routes

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
import org.openmbee.mms5.*

private val DEFAULT_CONDITIONS =  LOCK_CRUD_CONDITIONS.append {
    permit(Permission.DELETE_LOCK, Scope.LOCK)
}

fun Route.deleteLock() {
    delete("/orgs/{orgId}/repos/{repoId}/commit/{commitId}/locks/{lockId}") {
        call.mmsL1(Permission.DELETE_LOCK) {
            pathParams {
                org()
                repo()
                commit()
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

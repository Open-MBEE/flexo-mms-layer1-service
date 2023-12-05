package org.openmbee.flexo.mms.routes.ldp

import io.ktor.server.response.*
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.plugins.LdpDcLayer1Context
import org.openmbee.flexo.mms.plugins.LdpDeleteResponse

private val DEFAULT_CONDITIONS =  LOCK_CRUD_CONDITIONS.append {
    permit(Permission.DELETE_LOCK, Scope.LOCK)
}

suspend fun LdpDcLayer1Context<LdpDeleteResponse>.deleteLock() {
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

package org.openmbee.routes.endpoints

import io.ktor.application.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import org.openmbee.normalize
import org.openmbee.queryModel


@OptIn(InternalAPI::class)
fun Application.queryDiff() {
    routing {
        post("/orgs/{orgId}/repos/{repoId}/commits/{commitId}/locks/{lockId}/diff/{diffId}/query/{inspect?}") {
            val transaction = call.normalize {
                user()
                org()
                repo()
                commit()
                lock()
                diff()
            }

            val inspectValue = call.parameters["inspect"]?: ""
            val inspectOnly = if(inspectValue.isNotEmpty()) {
                if(inspectValue != "inspect") {
                    return@post call.respondText("", status = HttpStatusCode.NotFound)
                } else true
            } else false

            val prefixes = transaction.prefixes

            call.queryModel(transaction.requestBody, prefixes["morcld"]!!, prefixes, inspectOnly)
        }
    }
}

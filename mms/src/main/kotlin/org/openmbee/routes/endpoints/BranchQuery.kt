package org.openmbee.routes.endpoints

import io.ktor.application.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import org.openmbee.normalize
import org.openmbee.queryModel


@OptIn(InternalAPI::class)
fun Application.queryBranch() {
    routing {
        post("/orgs/{orgId}/repos/{repoId}/branches/{branchId}/query/{inspect?}") {
            val prefixes = call.normalize {
                user()
                org()
                repo()
                branch()
            }.prefixes

            val inspectValue = call.parameters["inspect"]?: ""
            val inspectOnly = if(inspectValue.isNotEmpty()) {
                if(inspectValue != "inspect") {
                    return@post call.respondText("", status = HttpStatusCode.NotFound)
                } else true
            } else false

            call.queryModel(prefixes["morb"]!!, prefixes, inspectOnly)
        }
    }
}

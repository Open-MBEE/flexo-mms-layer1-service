package org.openmbee.plugins

import io.ktor.application.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.locations.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import org.openmbee.HttpException
import org.openmbee.routes.*
import org.openmbee.routes.endpoints.commitBranch
import org.openmbee.routes.endpoints.queryBranch
import org.openmbee.routes.endpoints.queryDiff
import org.openmbee.routes.endpoints.queryLock


val client = HttpClient(CIO)


@OptIn(InternalAPI::class)
fun Application.configureRouting() {
    install(Locations) {}
    install(AutoHeadResponse)

    routing {
        install(StatusPages) {
            exception<HttpException> { cause ->
                cause.handle(call)
            }
            // exception<AuthenticationException> { cause ->
            //     call.respond(HttpStatusCode.Unauthorized)
            // }
            // exception<AuthorizationException> { cause ->
            //     call.respond(HttpStatusCode.Forbidden)
            // }
        }

        createOrg()
        readOrg()
        updateOrg()
        // deleteOrg()

        createCollection()
        // readCollection()
        // updateCollection()
        // deleteCollection()

        createRepo()
        readRepo()
        updateRepo()
        // deleteRepo()

        createBranch()
        commitBranch()
        queryBranch()
        // deleteBranch()

        createLock()
        queryLock()
        deleteLock()
        
        createDiff()
        queryDiff()
        // deleteDiff()
    }
}
// class AuthenticationException : RuntimeException()
// class AuthorizationException : RuntimeException()

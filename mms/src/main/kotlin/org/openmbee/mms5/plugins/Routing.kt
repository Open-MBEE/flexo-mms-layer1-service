package org.openmbee.mms5.plugins

import io.ktor.application.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.features.*
import io.ktor.locations.*
import io.ktor.routing.*
import io.ktor.util.*
import org.openmbee.mms5.HttpException
import org.openmbee.mms5.routes.*
import org.openmbee.mms5.routes.endpoints.*


val client = HttpClient(CIO)


@OptIn(InternalAPI::class)
fun Application.configureRouting() {
    install(Locations) {}
    // install(AutoHeadResponse)

    routing {
        // install(StatusPages) {
        //     exception<HttpException> { cause ->
        //         cause.handle(call)
        //     }
            // exception<AuthenticationException> { cause ->
            //     call.respond(HttpStatusCode.Unauthorized)
            // }
            // exception<AuthorizationException> { cause ->
            //     call.respond(HttpStatusCode.Forbidden)
            // }
        // }

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

        loadBranch()
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

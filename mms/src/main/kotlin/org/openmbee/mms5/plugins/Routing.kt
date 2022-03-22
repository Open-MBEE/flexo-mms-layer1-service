package org.openmbee.mms5.plugins

import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.locations.*
import io.ktor.routing.*
import io.ktor.util.*
import org.openmbee.mms5.routes.*
import org.openmbee.mms5.routes.endpoints.*
import org.openmbee.mms5.routes.gsp.readModel


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


        authenticate {
            createOrg()
            readOrg()
            updateOrg()
            // deleteOrg()

            createCollection()
            // readCollection()
            // updateCollection()
            // deleteCollection()

            queryCollection()

            createRepo()
            readRepo()
            updateRepo()
            // deleteRepo()

            createBranch()
            readBranch()
            updateBranch()
            // deleteBranch()

            loadModel()
            readModel()

            queryModel()
            commitModel()

            createLock()
            queryLock()
            deleteLock()

            createDiff()
            queryDiff()
            // deleteDiff()

            createLock()
            queryLock()
            deleteLock()

            createDiff()
            queryDiff()
            // deleteDiff()

            createLdapGroup()
            // updateLdapGroup()
            // deleteLdapGroup()

            // createPolicy()
            // updatePolicy()
            // deletePolicy()
        }
    }
}

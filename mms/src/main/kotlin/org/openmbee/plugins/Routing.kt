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
import org.openmbee.routes.readOrg
import org.openmbee.routes.readProject
import org.openmbee.routes.writeOrg
import org.openmbee.routes.writeProject


val client = HttpClient(CIO)


@OptIn(InternalAPI::class)
fun Application.configureRouting() {
    install(Locations) {}
    install(AutoHeadResponse)

    routing {
        install(StatusPages) {
            exception<AuthenticationException> { cause ->
                call.respond(HttpStatusCode.Unauthorized)
            }
            exception<AuthorizationException> { cause ->
                call.respond(HttpStatusCode.Forbidden)
            }
        }

        readOrg()
        writeOrg()

        readProject()
        writeProject()

        get("/") {
            call.respondText("Hello World!")
        }

        get<MyLocation> {
            call.respondText("Location: name=${it.name}, arg1=${it.arg1}, arg2=${it.arg2}")
        }

        // Register nested routes
        get<Type.Edit> {
            call.respondText("Inside $it")
        }

        get<Type.List> {
            call.respondText("Inside $it")
        }
    }
}
class AuthenticationException : RuntimeException()
class AuthorizationException : RuntimeException()

@Location("/location/{name}")
class MyLocation(val name: String, val arg1: Int = 42, val arg2: String = "default")

@Location("/type/{name}")
data class Type(val name: String) {
    @Location("/edit")
    data class Edit(val type: Type)

    @Location("/list/{page}")
    data class List(val type: Type, val page: Int)
}

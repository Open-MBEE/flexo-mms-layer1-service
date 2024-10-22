package org.openmbee.flexo.mms

import io.ktor.http.*
import io.ktor.server.response.*
import org.openmbee.flexo.mms.server.GenericResponse
import org.openmbee.flexo.mms.server.LdpDcLayer1Context

suspend fun LdpDcLayer1Context<GenericResponse>.notImplemented() {
    call.respondText(
        "That operation is not yet implemented",
        ContentType.Text.Plain,
        HttpStatusCode.NotImplemented
    )
}

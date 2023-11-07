package org.openmbee.mms5.plugins

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.*

import io.ktor.server.locations.*
import io.ktor.server.routing.*
import io.ktor.util.*
import io.ktor.util.reflect.*
import io.ktor.utils.io.*
import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.jvm.javaio.*
import org.openmbee.mms5.RdfContentTypes
import org.openmbee.mms5.requestTimeout
import org.openmbee.mms5.routes.*
import org.openmbee.mms5.routes.endpoints.*
import org.openmbee.mms5.routes.gsp.readModel


fun ApplicationCall.httpClient(timeoutOverrideMs: Long? = null): HttpClient {
    return HttpClient(CIO) {
        install(ContentNegotiation) {
            register(RdfContentTypes.Turtle, TextConverter())
            register(RdfContentTypes.JsonLd, TextConverter())
        }

        engine {
            requestTimeout = timeoutOverrideMs
                ?: this@httpClient.application.requestTimeout
                ?: (30 * 60 * 1000) // default timeout of 30 minutes
        }
    }
}


class TextConverter: ContentConverter {
    fun stringify(obj: Any?): String {
        if(obj == null) return ""
        return obj as String
    }

    override suspend fun deserialize(charset: Charset, typeInfo: TypeInfo, content: ByteReadChannel): Any? {
        return content.toInputStream().bufferedReader().use { it.readText() }
    }

    override suspend fun serializeNullable(
        contentType: ContentType,
        charset: Charset,
        typeInfo: TypeInfo,
        value: Any?
    ): OutgoingContent {
        return TextContent(this.stringify(value), contentType)
    }

    //
    // override suspend fun convertForReceive(context: PipelineContext<ApplicationReceiveRequest, ApplicationCall>): Any? {
    //     return context.context.receiveText()
    // }
    //
    // override suspend fun convertForSend(
    //     context: PipelineContext<Any, ApplicationCall>,
    //     contentType: ContentType,
    //     value: Any
    // ): Any? {
    //     return TextContent(this.stringify(value), contentType.withCharset(context.call.suitableCharset()))
    // }
}


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

            queryRepo()

            createBranch()
            readBranch()
            updateBranch()
            // deleteBranch()

            loadModel()
            readModel()

            queryModel()
            commitModel()

            createLock()
            readLock()
            deleteLock()

            queryLock()

            createDiff()
            queryDiff()
            // deleteDiff()

            createGroup()
            // updateGroup()
            // deleteGroup()

            createPolicy()
            // updatePolicy()
            // deletePolicy()
        }
    }
}

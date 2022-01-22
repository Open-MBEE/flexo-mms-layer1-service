package org.openmbee

import io.ktor.application.*
import io.ktor.request.*
import org.openmbee.plugins.AuthorizationException
import org.openmbee.plugins.configureHTTP
import org.openmbee.plugins.configureRouting

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

fun Application.module(testing: Boolean=false) {
    configureHTTP()
    configureRouting()
}

//fun main() {
//    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
//        configureHTTP()
//        configureRouting()
//    }.start(wait = true)
//}

class AuthorizationRequiredException(message: String): Exception(message) {}

class Normalizer(val call: ApplicationCall) {
    val userId = call.mmsUserId
    var orgId: String? = null
    var repoId: String? = null
    var commitId: String? = null
    var lockId: String? = null
    var branchId: String? = null

    fun user(): Normalizer {
        // missing userId
        if(userId.isEmpty()) {
            throw AuthorizationRequiredException("Missing header: `MMS5-User`")
        }
        return this
    }

    fun org(legal: Boolean=false): Normalizer {
        orgId = call.parameters["orgId"]
        if(legal) assertLegalId(repoId!!)
        return this
    }

    fun repo(legal: Boolean=false): Normalizer {
        repoId = call.parameters["repoId"]
        if(legal) assertLegalId(repoId!!)
        return this
    }

    fun commit(legal: Boolean=false): Normalizer {
        commitId = call.parameters["commitId"]
        if(legal) assertLegalId(repoId!!)
        return this
    }

    fun lock(legal: Boolean=false): Normalizer {
        lockId = call.parameters["lockId"]
        if(legal) assertLegalId(repoId!!)
        return this
    }

    fun branch(legal: Boolean=false): Normalizer {
        branchId = call.parameters["branchId"]
        if(legal) assertLegalId(repoId!!)
        return this
    }
}

suspend fun ApplicationCall.normalize(setup: Normalizer.()->Normalizer): TransactionContext {
    val norm = Normalizer(this).setup()

    val body = receiveText()

    return TransactionContext(
        userId = norm.userId,
        orgId = norm.orgId,
        repoId = norm.repoId,
        commitId = norm.commitId,
        lockId = norm.lockId,
        branchId = norm.branchId,
        request = request,
        requestBody = body,
    )
}

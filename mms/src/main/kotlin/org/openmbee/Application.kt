package org.openmbee

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.application.*
import org.openmbee.plugins.*

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

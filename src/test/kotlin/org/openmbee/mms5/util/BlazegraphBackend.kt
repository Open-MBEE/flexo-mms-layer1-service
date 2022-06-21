package org.openmbee.mms5.util

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * Runs a Blazegraph server in a separate process. Not used anymore and probably
 * can be deleted, but kept here for reference.
 */
class BlazegraphBackend : SparqlBackend {
    private val server = HttpServer.create()
    private val id = UUID.randomUUID().toString()
    private var started = false

    override fun start() {
        if (started) {
            throw IllegalStateException("Already started")
        }
        started = true
        server.bind(InetSocketAddress(0), 16)
        server.createContext("/") { exchange ->
            exchange.responseBody.writer(StandardCharsets.UTF_8).use { writer ->
                writer.write(id)
            }
        }
        val cmd = listOf(
            System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
            "-cp",
            System.getProperty("java.class.path"),
            javaClass.name + "Kt",
            id,
            server.address.port.toString()
        )
        val workingDir = File("build" + File.separator + "blazegraph")
        if (!workingDir.exists()) {
            workingDir.mkdirs()
        }
        File(workingDir, "blazegraph.jnl").delete()
        File(workingDir, "rules.log").delete()
        val p = ProcessBuilder(cmd)
            .directory(workingDir)
            .start()
        val s = Scanner(p.inputStream)
        println("Waiting for BlazeGraph...")
        while (s.hasNext()) {
            val line = s.nextLine()
            println(line)
            if (line.startsWith("Go to")) {
                break
            }
        }
        println("BlazeGraph Ready")
    }

    override fun stop() {
        server.stop(0)
    }

    override fun saveToFile() {
        TODO("Not yet implemented")
    }

    override fun getQueryUrl(): String {
        return "http://localhost:9999/blazegraph/sparql"
    }

    override fun getUpdateUrl(): String {
        return getQueryUrl();
    }

    override fun getUploadUrl(): String {
        return getQueryUrl();
    }
}

fun main(args: Array<String>) {
    val cmd = listOf(
        System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
        "-jar",
        "/Users/gaspardo/Downloads/blazegraph.jar"
    )
    println("[START BLAZEGRAPH]")
    val p = ProcessBuilder(cmd)
        .inheritIO()
        .start()
    try {
        var client = HttpClient.newHttpClient()
        while (true) {
            val req = HttpRequest.newBuilder(URI("http://localhost:" + args[1]))
                .GET()
                .build()
            var res = client.send(req, BodyHandlers.ofString())
            if (res.body().trim() != args[1]) {
                throw IllegalStateException("Unexpected ID")
            }
            Thread.sleep(100)
        }
    } catch (e: Exception) {
        println("[STOP BLAZEGRAPH BECAUSE ${e.message}]")
    } finally {
        p.destroy()
    }
}
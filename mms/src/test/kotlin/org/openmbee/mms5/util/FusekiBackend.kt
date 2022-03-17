package org.openmbee.mms5.util

import java.io.File
import java.net.ServerSocket
import java.net.URLClassLoader

/**
 * Fuseki SPARQL backend. Runs fuseki-server JAR in an isolated ClassLoader using an in-memory dataset.
 */
class FusekiBackend : SparqlBackend {

    /** FusekiServer object */
    private val fusekiServer: Any

    /** Port to use for fuseki. Finds a random open port */
    private val fusekiPort: Int = ServerSocket(0).use { it.localPort }

    /**
     * Create Fuseki Server using an isolated ClassLoader to avoid conflicts with implementation classpath
     */
    init {
        val fusekiJarUrls = (File("build" + File.separator + "test-fuseki-server").listFiles() ?: arrayOf<File>())
            .filter { it.extension.lowercase() == "jar" }
            .map { it.toURI().toURL() }
            .toTypedArray()
        if (fusekiJarUrls.isEmpty()) {
            throw IllegalStateException("No fuseki-server JAR Found; run gradle build to initialize")
        }
        val bootstrapClassLoader = ClassLoader.getSystemClassLoader().parent
        val fusekiClassLoader = URLClassLoader(fusekiJarUrls, bootstrapClassLoader)

        // val dsg = new DatasetGraphInMemory()
        val dsg = Class.forName("org.apache.jena.sparql.core.mem.DatasetGraphInMemory", true, fusekiClassLoader)
            .getConstructor().newInstance()

        // fusekiServer = FusekiServer.make(fusekiPort, "/ds", dsg)
        fusekiServer = Class.forName("org.apache.jena.fuseki.main.FusekiServer", true, fusekiClassLoader)
            .getMethod("make", Integer.TYPE, Class.forName("java.lang.String"), Class.forName("org.apache.jena.sparql.core.DatasetGraph", false, fusekiClassLoader))
            .invoke(null, fusekiPort, "/ds", dsg);
    }

    override fun start() {
        // fusekiServer.start()
        fusekiServer.javaClass.getMethod("start").invoke(fusekiServer)
    }

    override fun stop() {
        // fusekiServer.stop()
        fusekiServer.javaClass.getMethod("stop").invoke(fusekiServer)
    }

    override fun getQueryUrl(): String {
        return "http://localhost:${fusekiPort}/ds/query"
    }

    override fun getUpdateUrl(): String {
        return "http://localhost:${fusekiPort}/ds/update"
    }

    override fun getUploadUrl(): String {
        return "http://localhost:${fusekiPort}/ds"
    }

}
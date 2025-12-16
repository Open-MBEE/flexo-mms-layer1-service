package org.openmbee.flexo.mms

import org.apache.jena.fuseki.main.FusekiServer
import org.apache.jena.query.Dataset
import org.apache.jena.rdfconnection.RDFConnection
import org.apache.jena.rdfconnection.RDFConnectionFactory
import org.apache.jena.riot.Lang
import org.apache.jena.riot.RDFDataMgr
import org.apache.jena.tdb2.TDB2Factory
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.concurrent.thread

/**
 * Launcher that starts an embedded Fuseki server and then launches the Layer1 service.
 * This allows running both the quadstore and the MMS Layer1 service in a single JAR.
 */
object EmbeddedFusekiLauncher {
    private const val DEFAULT_FUSEKI_PORT = 3030
    private const val DEFAULT_LAYER1_PORT = 31337
    private const val DEFAULT_DATASET_NAME = "ds"
    private const val DEFAULT_LAYER1_ROOT_CONTEXT = "http://localhost:31337"

    private var fusekiServer: FusekiServer? = null

    @JvmStatic
    fun main(args: Array<String>) {
        println("Starting Flexo MMS Layer1 with embedded Fuseki server...")

        // Parse configuration from environment or system properties
        val fusekiPort = System.getenv("FUSEKI_PORT")?.toIntOrNull()
            ?: System.getProperty("fuseki.port")?.toIntOrNull()
            ?: DEFAULT_FUSEKI_PORT

        val datasetName = System.getenv("FUSEKI_DATASET_NAME")
            ?: System.getProperty("fuseki.dataset.name")
            ?: DEFAULT_DATASET_NAME

        val persistenceMode = System.getenv("FUSEKI_PERSISTENCE_MODE")
            ?: System.getProperty("fuseki.persistence.mode")
            ?: "memory"

        val tdbLocation = System.getenv("FUSEKI_TDB_LOCATION")
            ?: System.getProperty("fuseki.tdb.location")
            ?: "./fuseki-data"

        val layer1Port = System.getenv("FLEXO_MMS_LAYER1_PORT")
            ?: System.getenv("FLEXO_LAYER1_PORT")  // Fallback for backward compatibility
            ?: System.getenv("LAYER1_PORT")  // Fallback for backward compatibility
            ?: System.getProperty("flexo.mms.layer1.port")
            ?: DEFAULT_LAYER1_PORT.toString()

        val rootContext = System.getenv("FLEXO_MMS_ROOT_CONTEXT")
            ?: System.getProperty("flexo.mms.root.context")
            ?: "http://localhost:$layer1Port"

        // Create dataset based on persistence mode
        val dataset = when (persistenceMode.lowercase()) {
            "tdb2", "persistent" -> {
                println("Using persistent TDB2 dataset at: $tdbLocation")
                Files.createDirectories(Paths.get(tdbLocation))
                TDB2Factory.connectDataset(tdbLocation)
            }
            else -> {
                println("Using in-memory dataset")
                TDB2Factory.createDataset()
            }
        }

        // Start Fuseki server
        fusekiServer = FusekiServer.create()
            .port(fusekiPort)
            .add("/$datasetName", dataset)
            .build()

        fusekiServer?.start()
        println("Fuseki server started on port $fusekiPort with dataset '/$datasetName'")

        // Set up shutdown hook
        Runtime.getRuntime().addShutdownHook(thread(start = false) {
            println("Shutting down Fuseki server...")
            fusekiServer?.stop()
        })

        // Load initialization data if cluster.trig exists
        loadInitializationData(fusekiPort, datasetName)

        // Set system properties for Layer1 service to connect to embedded Fuseki
        val baseUrl = "http://localhost:$fusekiPort/$datasetName"
        System.setProperty("FLEXO_MMS_ROOT_CONTEXT", rootContext)
        System.setProperty("FLEXO_MMS_QUERY_URL", "$baseUrl/sparql")
        System.setProperty("FLEXO_MMS_UPDATE_URL", "$baseUrl/update")
        System.setProperty("FLEXO_MMS_GRAPH_STORE_PROTOCOL_URL", "$baseUrl/data")

        // Set PORT system property for Ktor (use LAYER1_PORT if PORT not set)
        if (System.getProperty("PORT") == null && System.getenv("PORT") == null) {
            System.setProperty("PORT", layer1Port)
        }

        println("Environment configured:")
        println("  Fuseki Port: $fusekiPort")
        println("  Layer1 Port: ${System.getProperty("PORT") ?: System.getenv("PORT") ?: layer1Port}")
        println("  FLEXO_MMS_ROOT_CONTEXT: $rootContext")
        println("  FLEXO_MMS_QUERY_URL: $baseUrl/sparql")
        println("  FLEXO_MMS_UPDATE_URL: $baseUrl/update")
        println("  FLEXO_MMS_GRAPH_STORE_PROTOCOL_URL: $baseUrl/data")
        println()

        // Start the Layer1 application
        println("Starting Flexo MMS Layer1 service...")
        io.ktor.server.netty.EngineMain.main(args)
    }

    private fun loadInitializationData(fusekiPort: Int, datasetName: String) {
        try {
            // Try to load cluster.trig from classpath
            val clusterTrigStream = EmbeddedFusekiLauncher::class.java.classLoader
                .getResourceAsStream("cluster.trig")

            if (clusterTrigStream != null) {
                println("Loading initialization data from cluster.trig...")

                // Write stream to a temporary file
                val tempFile = File.createTempFile("cluster", ".trig")
                tempFile.deleteOnExit()
                clusterTrigStream.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                val gspUrl = "http://localhost:$fusekiPort/$datasetName"
                RDFConnection.connect(gspUrl).use { conn ->
                    conn.putDataset(tempFile.absolutePath)
                }

                println("Initialization data loaded successfully")
            } else {
                // Check if external cluster.trig file exists
                val externalFile = File("cluster.trig")
                if (externalFile.exists()) {
                    println("Loading initialization data from external cluster.trig...")

                    val gspUrl = "http://localhost:$fusekiPort/$datasetName"
                    RDFConnection.connect(gspUrl).use { conn ->
                        conn.putDataset(externalFile.absolutePath)
                    }

                    println("Initialization data loaded successfully")
                } else {
                    println("WARNING: No cluster.trig initialization file found.")
                    println("The system may not function correctly without initialization data.")
                    println("You can provide cluster.trig in the current directory or generate one using the deploy tool.")
                }
            }
        } catch (e: Exception) {
            println("ERROR: Failed to load initialization data: ${e.message}")
            e.printStackTrace()
        }
    }
}

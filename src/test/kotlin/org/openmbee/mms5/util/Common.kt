package org.openmbee.mms5.util

import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.jena.rdfconnection.RDFConnection
import org.apache.jena.riot.RDFDataMgr
import org.apache.jena.riot.RDFFormat
import org.apache.jena.sparql.exec.http.UpdateExecutionHTTP
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream


val backend = RemoteBackend()

open class CommonSpec() : StringSpec({
    val logger = LoggerFactory.getLogger(CommonSpec::class.java)
    val clusterFilePath = File(javaClass.classLoader.getResource("cluster.trig")!!.file).absolutePath

    beforeSpec {
        backend.start()
    }

    beforeEach {
        // drop all graphs
        UpdateExecutionHTTP.service(backend.getUpdateUrl()).update("drop all").execute()

        // reinitialized with cluster.trig
        RDFConnection.connect(backend.getGspdUrl()).use {
            it.putDataset(clusterFilePath)
        }
    }

    afterEach { it ->
        val exportFile = File("/application/build/reports/tests/trig/${it.a.name.testName}.trig")

        if (!exportFile.parentFile.exists())
            exportFile.parentFile.mkdirs()
        if (!exportFile.exists())
            withContext(Dispatchers.IO) {
                exportFile.createNewFile()
            }

        val out = withContext(Dispatchers.IO) {
            FileOutputStream(exportFile.absoluteFile)
        }
        // // dump all graphs
        RDFConnection.connect(backend.getGspdUrl()).use {
            RDFDataMgr.write(out, it.fetchDataset(), RDFFormat.TRIG)
        }
    }

    afterSpec {
        backend.stop()
    }
})


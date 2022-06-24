package org.openmbee.mms5.util

import io.kotest.core.spec.style.StringSpec
import org.apache.jena.rdfconnection.RDFConnection
import org.apache.jena.riot.RDFDataMgr
import org.apache.jena.riot.RDFFormat
import org.apache.jena.sparql.exec.http.UpdateExecutionHTTP
import org.slf4j.LoggerFactory
import java.io.File


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

    afterEach {
        // // dump all graphs
        // RDFConnection.connect(backend.getGspdUrl()).use {
        //     RDFDataMgr.write(outputStream, it.fetchDataset(), RDFFormat.TRIG)
        // }
    }

    afterSpec {
        backend.stop()
    }
})


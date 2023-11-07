package org.openmbee.flexo.mms.util

import io.kotest.core.NamedTag
import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.jena.rdfconnection.RDFConnection
import org.apache.jena.riot.RDFDataMgr
import org.apache.jena.riot.RDFFormat
import org.apache.jena.sparql.exec.http.UpdateExecutionHTTP
import java.io.File
import java.io.FileOutputStream

val NoAuth = NamedTag("NoAuth")

val Expect404 = NamedTag("Expect404")

val backend = RemoteBackend()

fun escapeFileName(name: String): String {
    return name.replace("""\s+""".toRegex(), "-")
        .replace("""[^a-z0-9A-Z-]""".toRegex(), "_")
}

open class CommonSpec : StringSpec() {
    val clusterFilePath: String = File(javaClass.classLoader.getResource("cluster.trig")!!.file).absolutePath

    override suspend fun beforeSpec(spec: Spec) {
        super.beforeSpec(spec)
        backend.start()
    }

    override suspend fun beforeEach(testCase: TestCase) {
        super.beforeEach(testCase)

        // drop all graphs
        UpdateExecutionHTTP.service(backend.getUpdateUrl()).update("drop all").execute()

        // reinitialized with cluster.trig
        RDFConnection.connect(backend.getGspUrl()).use {
            it.putDataset(clusterFilePath)
        }
    }

    override suspend fun afterEach(testCase: TestCase, result: TestResult) {
        super.afterEach(testCase, result)

        // prep output file
        val exportFile = File("build/reports/tests/trig/${escapeFileName(testCase.name.testName)}.trig")

        if (!exportFile.parentFile.exists())
            exportFile.parentFile.mkdirs()
        if (!exportFile.exists())
            withContext(Dispatchers.IO) {
                exportFile.createNewFile()
            }

        // create output stream
        val out = withContext(Dispatchers.IO) {
            FileOutputStream(exportFile.absoluteFile)
        }

        // dump all graphs
        RDFConnection.connect(backend.getGspUrl()).use {
            RDFDataMgr.write(out, it.fetchDataset(), RDFFormat.TRIG)
        }
    }

    override suspend fun afterSpec(spec: Spec) {
        super.afterSpec(spec)
        backend.stop()
    }
}


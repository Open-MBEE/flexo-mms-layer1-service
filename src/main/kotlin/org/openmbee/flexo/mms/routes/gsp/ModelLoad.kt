import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.utils.io.*
import org.apache.jena.rdf.model.Property
import org.apache.jena.rdf.model.Resource
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.routes.sparql.*
import org.openmbee.flexo.mms.server.GspLayer1Context
import org.openmbee.flexo.mms.server.GspMutateResponse

private val DEFAULT_UPDATE_CONDITIONS = BRANCH_COMMIT_CONDITIONS

suspend fun GspLayer1Context<GspMutateResponse>.loadModel() {
    // check path parameters
    parsePathParams {
        org()
        repo()
        branch()
    }

    // prep conditions
    val localConditions = DEFAULT_UPDATE_CONDITIONS.append {
        // assert HTTP preconditions
        assertPreconditions(this) {
            """
                graph mor-graph:Metadata {
                    morb: mms:etag ?__mms_etag .
                    $it
                }
            """
        }
    }
    createBranchModifyingTransaction(localConditions)
    try {
        val txnModel = validateBranchModifyingTransaction(localConditions)
        val stagingGraphIri = txnModel.listObjectsOfProperty(
            txnModel.createResource(prefixes["mt"]), MMS.TXN.stagingGraph)
            .next().asResource().uri
        val baseCommitIri = txnModel.listObjectsOfProperty(
            txnModel.createResource(prefixes["mt"]), MMS.TXN.baseCommit)
            .next().asResource().uri

        // prepare IRI for named graph to hold loaded model
        val loadGraphUri = "${prefixes["mor-graph"]}Load.$transactionId"

        // now load triples into designated load graph
        loadGraph(loadGraphUri, "$orgId/$repoId/$branchId/$diffId.ttl")
        // replace current staging graph with the already loaded model in load graph
        val commitUpdateString = genCommitUpdate(
            delete = """
                graph mor-graph:Metadata {
                    ?staging mms:graph <$stagingGraphIri> .
                }
            """,
            insert = """
                graph mor-graph:Metadata {
                    ?staging mms:graph <$loadGraphUri> . 
                }
            """,
            where = """
                graph mor-graph:Metadata {
                    morb: mms:snapshot ?staging .
                    ?staging a mms:Staging .
                }
            """
        )
        val constructCommitResponseText = diffAndFinalizeCommit(
            loadGraphUri,
            stagingGraphIri,
            baseCommitIri,
            localConditions,
            commitUpdateString)
        if (constructCommitResponseText.isBlank()) {
            //there's no diff, already responded
            deleteTransaction()
            executeSparqlUpdate("""
                drop graph <$loadGraphUri>;
            """)
            return
        }
        // sanity check
        log("Sending construct response text to client: \n$constructCommitResponseText\n")

        // response
        call.response.header(HttpHeaders.ETag, transactionId)
        call.response.header(HttpHeaders.Location, prefixes["morc"]!!)
        call.respondText(constructCommitResponseText, contentType = RdfContentTypes.Turtle)

        deleteTransaction()
        executeSparqlUpdate("""
            drop graph <$stagingGraphIri>;
        """)
    } catch(e: Exception) {
        // finally is not used here since deleteTransaction is manually called before graph deletion in the try block
        deleteTransaction()
        throw e
    }
}


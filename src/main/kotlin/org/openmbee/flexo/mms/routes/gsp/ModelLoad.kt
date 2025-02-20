import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.jena.rdf.model.Property
import org.apache.jena.rdf.model.Resource
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.MMS.TXN.stagingGraph
import org.openmbee.flexo.mms.routes.sparql.compressStringLiteral
import org.openmbee.flexo.mms.routes.sparql.genCommitUpdate
import org.openmbee.flexo.mms.routes.sparql.genDiffUpdate
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

    // set diff id
    diffId = "Load.$transactionId"

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
    run {
        // allow client to manually pass in URL to remote file
        var loadUrl: String? = call.request.queryParameters["url"]
        var storeServiceUrl: String? = call.application.storeServiceUrl

        // client did not explicitly provide a URL and the store service is configured
        if (loadUrl == null && storeServiceUrl != null) {
            // submit a POST request to the store service endpoint
            val response: HttpResponse = defaultHttpClient.post("$storeServiceUrl/$diffId") {
                // TODO: verify store service request is correct and complete
                // Pass received authorization to internal service
                headers {
                    call.request.headers[HttpHeaders.Authorization]?.let { auth: String ->
                        append(HttpHeaders.Authorization, auth)
                    }
                }
                // stream request body from client to store service
                // TODO: Handle exceptions
                setBody(object : OutgoingContent.WriteChannelContent() {
                    override val contentType = call.request.contentType()
                    override val contentLength = call.request.contentLength() ?: 0L
                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        call.request.receiveChannel().copyTo(channel)
                    }
                })
            }

            // read response body
            val responseText = response.bodyAsText()

            // non-200
            if (!response.status.isSuccess()) {
                throw Non200Response(responseText, response.status)
            }

            // set load URL
            loadUrl = responseText
        }

        // a load URL has been set
        if (loadUrl != null) {
            // parse types the store service backend accepts
            val acceptTypes = parseAcceptTypes(call.application.storeServiceAccepts)

            // confirm that store service/load supports content-type
            if (!acceptTypes.contains(requestContext.requestContentType)) {
                throw UnsupportedMediaType("Store/LOAD backend does not support ${requestContext.responseContentType}")
            }

            // use SPARQL LOAD
            val loadUpdateString = buildSparqlUpdate {
                raw("""
                    load ?_loadUrl into graph ?_loadGraph
                """.trimIndent())
            }

            log("Loading <$loadUrl> into <$loadGraphUri> via: `$loadUpdateString`")

            executeSparqlUpdate(loadUpdateString) {
                prefixes(prefixes)

                iri(
                    "_loadUrl" to loadUrl,
                    "_loadGraph" to loadGraphUri,
                )
            }

            // exit load block
            return@run
        }

        // GSP is configured; use it
        if (call.application.quadStoreGraphStoreProtocolUrl != null) {
            // parse types the gsp backend accepts
            val acceptTypes = parseAcceptTypes(call.application.quadStoreGraphStoreProtocolAccepts)

            // confirm that backend supports content-type
            if (!acceptTypes.contains(requestContext.requestContentType)) {
                throw UnsupportedMediaType("GSP backend does not support loading ${requestContext.responseContentType}")
            }

            // submit a PUT request to the quad-store's GSP endpoint
            val response: HttpResponse = defaultHttpClient.put(call.application.quadStoreGraphStoreProtocolUrl!!) {
                // add the graph query parameter per the GSP specification
                parameter("graph", loadGraphUri)

                // stream request body from client to GSP endpoint
                setBody(object : OutgoingContent.WriteChannelContent() {
                    // forward the header for the content type, or default to turtle
                    override val contentType = requestContext.requestContentType

                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        call.request.receiveChannel().copyTo(channel)
                    }
                })
            }

            // read response body
            val responseText = response.bodyAsText()

            // non-200
            if (!response.status.isSuccess()) {
                throw Non200Response(responseText, response.status)
            }
        }
        // fallback to SPARQL UPDATE string
        else {
            // fully load request body
            val body = call.receiveText()

            // parse it into a model
            val model = KModel(prefixes).apply {
                parseRdfByContentType(requestContext.requestContentType!!, body, this)

                // clear the prefix map so that stringified version uses full IRIs
                clearNsPrefixMap()
            }

            // serialize model into turtle
            val loadUpdateString = buildSparqlUpdate {
                insert {
                    // embed the model in a triples block within the update
                    raw("""
                        # user model
                        graph ?_loadGraph {
                            ${model.stringify()}
                        }
                    """)
                }
            }

            // execute
            executeSparqlUpdate(loadUpdateString) {
                prefixes(prefixes)

                iri(
                    "_loadGraph" to loadGraphUri,
                )
            }
        }
    }


    // compute the delta
    run {
        val updateString = genDiffUpdate()
        executeSparqlUpdate(updateString) {
            prefixes(prefixes)

            iri(
                // use current branch as ref source
                "srcRef" to prefixes["morb"]!!,

                // set dst graph
                "dstGraph" to loadGraphUri,

                // set dst commit (this commit)
                "dstCommit" to prefixes["morc"]!!,

                // use explicit srcGraph
                "srcGraph" to stagingGraphIri,

                // use explicit srcCommit
                "srcCommit" to baseCommitIri,
            )
        }
    }

    // validate diff creation
    lateinit var diffConstructResponseText: String
    val diffConstructModel = run {
        val diffConstructString = buildSparqlQuery {
            construct {
                txn("diff")
                etag("morb:")
            }
            where {
                group {
                    txn("diff", true)
                    etag("morb:")
                }
            }
        }

        diffConstructResponseText = executeSparqlConstructOrDescribe(diffConstructString)

        log("Diff construct response:\n$diffConstructResponseText")

        validateTransaction(diffConstructResponseText, localConditions, "diff")
    }


    // shortcut lambda for fetching properties in returned model
    val propertyUriAt = { res: Resource, prop: Property ->
        diffConstructModel.listObjectsOfProperty(res, prop).let {
            if (it.hasNext()) it.next().asResource().uri else null
        }
    }

    // locate transaction node
    val transactionNode = diffConstructModel.createResource(prefixes["mt"] + "diff")


    // get ins/del graphs
    val diffInsGraph = propertyUriAt(transactionNode, MMS.TXN.insGraph)
    val diffDelGraph = propertyUriAt(transactionNode, MMS.TXN.delGraph)

    // parse the result value
    val changeCount = if (diffInsGraph == null && diffDelGraph == null) {
        0uL
    } else {
        log("Changes detected between previous commit and uploaded model")

        // count the number of changes in the diff
        val selectDiffResponseText = executeSparqlSelectOrAsk("""
            select (count(*) as ?changeCount) {
                {
                    graph ?_insGraph {
                        ?ins_s ?ins_p ?ins_o .
                    }
                } union {
                    graph ?_delGraph {
                        ?del_s ?del_p ?del_o .
                    }
                }
            }
        """) {
            prefixes(prefixes)

            iri(
                "_insGraph" to (diffInsGraph ?: "mms:voidInsGraph"),
                "_delGraph" to (diffDelGraph ?: "mms:voidDelGraph"),
            )
        }

        log("Change count select response:\n$selectDiffResponseText")

        // parse the JSON response
        val bindings =
            Json.parseToJsonElement(selectDiffResponseText).jsonObject["results"]!!.jsonObject["bindings"]!!.jsonArray

        // query error
        if (0 == bindings.size) {
            throw ServerBugException("Query to count the number of triples in the diff graphs failed to return any results")
        }

        // bind result to outer assignment
        bindings[0].jsonObject["changeCount"]!!.jsonObject["value"]!!.jsonPrimitive.content.toULong()
    }

    // empty delta (no changes)
    /*  ignored for now for neptune workaround
    if(changeCount == 0uL) {
        // locate branch node
        val branchNode = diffConstructModel.createResource(prefixes["morb"])

        // get its etag value
        val branchFormerEtagValue = branchNode.getProperty(MMS.etag).`object`!!.asLiteral().string

        // set etag header
        call.response.header(HttpHeaders.ETag, branchFormerEtagValue)

        // sanity check
        log.info("Sending data-less construct response text to client: \n$prefixes")

        // respond
        call.respondText("$prefixes", RdfContentTypes.Turtle)
    }
    else {
    */
    // TODO add condition to update that the selected staging has not changed since diff creation using etag value

    val deleteDataResponseText = executeSparqlConstructOrDescribe("""
        construct {
            ?del_s ?del_p ?del_o .
        }
        where {                    
            graph ?_delGraph {
                ?del_s ?del_p ?del_o .
            }
        }
    """) {
        iri(
            "_delGraph" to diffDelGraph!!,
        )
    }

    // create patch string
    val insertDataResponseText = executeSparqlConstructOrDescribe("""
        construct {
            ?ins_s ?ins_p ?ins_o .
        }
        where {                    
            graph ?_insGraph {
                ?ins_s ?ins_p ?ins_o .
            }
        }
    """) {
        iri(
            "_insGraph" to diffInsGraph!!,
        )
    }


    // replace current staging graph with the already loaded model in load graph
    val commitUpdateString = genCommitUpdate(
        delete = """
            graph mor-graph:Metadata {
                ?staging mms:graph ?_stagingGraph .
            }
        """,
        insert = """
            graph mor-graph:Metadata {
                ?staging mms:graph ?_loadGraph . 
            }
        """,
        where = """
            graph mor-graph:Metadata {
                morb: mms:snapshot ?staging .
                ?staging a mms:Staging .
            }
        """
    )

    var patchString = """
        delete data {
            graph ?__mms_model {
                $deleteDataResponseText
            }
        } ;
        insert data {
            graph ?__mms_model {
                $insertDataResponseText
            }
        }
    """.trimIndent()

    var patchStringDatatype = MMS_DATATYPE.sparql

    val originalKiB = patchString.length / 1024f;
    if (call.application.gzipLiteralsLargerThanKib?.let { originalKiB > it } == true) {
        val originalMiB = originalKiB / 1024f

        // compress string
        val patchStringCompressed = compressStringLiteral(patchString)

        // compression accepted
        if (patchStringCompressed != null) {
            // verbose
            log(
                "Patch string compressed from ${"%.2f".format(originalMiB)} MiB to ${
                    "%.2f".format(patchStringCompressed.length / 1024f / 1024f)
                } MiB"
            )

            patchString = patchStringCompressed
            patchStringDatatype = MMS_DATATYPE.sparqlGz
        }
    }

    // still greater than safe maximum
    if (call.application.maximumLiteralSizeKib?.let { patchString.length > it } == true) {
        log("Compressed patch string still too large")

        // TODO: store as delete and insert graphs...

        // otherwise, just give up
        patchString = "<urn:mms:omitted> <urn:mms:too-large> <urn:mms:to-handle> ."
    }
    log("Prepared commit update string:")
    executeSparqlUpdate(commitUpdateString) {
        prefixes(prefixes)

        iri(
            "_insGraph" to (diffInsGraph ?: "mms:voidInsGraph"),
            "_delGraph" to (diffDelGraph ?: "mms:voidDelGraph"),
            "_loadGraph" to loadGraphUri,
            "_stagingGraph" to stagingGraphIri
        )

        datatyped(
            "_updateBody" to ("" to MMS_DATATYPE.sparql),
            "_patchString" to (patchString to patchStringDatatype),
            "_whereString" to ("" to MMS_DATATYPE.sparql),
        )

        literal(
            "_txnId" to transactionId,
        )
    }

    val constructCommitString = buildSparqlQuery {
        construct {
            raw("""
                morc: ?commit_p ?commit_o .
            """)
        }
        where {
            raw("""
                graph mor-graph:Metadata {
                   morc: ?commit_p ?commit_o .
                }
            """)
        }
    }
    val constructCommitResponseText = executeSparqlConstructOrDescribe(constructCommitString)

    // sanity check
    log("Sending construct response text to client: \n$constructCommitResponseText\n$diffConstructResponseText")

    //log("Sending commit construct response text to client: \n$diffConstructResponseText")
    // response
    call.response.header(HttpHeaders.ETag, transactionId)
    call.response.header(HttpHeaders.Location, prefixes["morc"]!!)
    call.respondText("$constructCommitResponseText\n$diffConstructResponseText", RdfContentTypes.Turtle)

    //
    // ==== Response closed ====
    //

    // start copying staging to new model
    executeSparqlUpdate("""
        copy graph ?_loadGraph to graph ?_modelGraph ;

        insert data {
            graph m-graph:Graphs {
                ?_modelGraph a mms:SnapshotGraph .
            }

            graph mor-graph:Metadata {
                mor-lock:Commit.${transactionId} a mms:Lock ;
                    mms:snapshot ?_model ;
                    mms:commit morc: ;
                    .
                ?_model a mms:Model ;
                    mms:graph ?_modelGraph ;
                    .
            }
        }
    """) {
        prefixes(prefixes)

        iri(
            "_loadGraph" to loadGraphUri,
            "_model" to "${prefixes["mor-snapshot"]}Model.${transactionId}",
            "_modelGraph" to "${prefixes["mor-graph"]}Model.${transactionId}",
        )
    }

    //}

    // now that response has been sent to client, perform "clean up" work on quad-store
    run {
        // delete orphaned previous staging graph
        executeSparqlUpdate("""
            drop graph <$stagingGraphIri>;
        """)
    }
    } finally {
        executeSparqlUpdate("""
            delete where {
                graph m-graph:Transactions {
                    mt:diff ?diff_p ?diff_o .
                    mt: ?p ?o .
                }
            }
        """)
    }
}


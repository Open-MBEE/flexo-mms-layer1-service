package org.openmbee.flexo.mms.routes.ldp

import io.ktor.http.*
import io.ktor.server.response.*
import org.apache.jena.datatypes.xsd.XSDDatatype
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.server.LdpDcLayer1Context
import org.openmbee.flexo.mms.server.LdpMutateResponse
import java.time.Instant


// default starting conditions for any calls to create a branch
private val DEFAULT_CONDITIONS = REPO_CRUD_CONDITIONS.append {
    // require that the user has the ability to create branches on a repo-level scope
    permit(Permission.CREATE_BRANCH, Scope.REPO)

    // require that the given branch does not exist before attempting to create it
    require("branchNotExists") {
        handler = { layer1 -> "The provided branch <${layer1.prefixes["morb"]}> already exists." to HttpStatusCode.BadRequest }

        """
            # branch must not yet exist
            filter not exists {
                graph mor-graph:Metadata {
                    morb: a mms:Branch .
                }
            }
        """
    }
}



//suspend fun AnyLayer1Context.createBranch(usedPost: Boolean = false) {
suspend fun <TResponseContext: LdpMutateResponse> LdpDcLayer1Context<TResponseContext>.createBranch(usedPost: Boolean = false) {
    // process RDF body from user about this new branch
    val branchTriples = filterIncomingStatements("morb") {
        // relative to this branch node
        branchNode().apply {
            // assert and normalize ref/commit triples
            normalizeRefOrCommit(this)

            // sanitize statements
            sanitizeCrudObject {
                setProperty(RDF.type, MMS.Branch)
                setProperty(MMS.id, branchId!!)
                setProperty(MMS.etag, transactionId)
                setProperty(MMS.createdBy, userNode())
            }
        }
    }

    // extend the default conditions with requirements for user-specified ref or commit
    val localConditions = DEFAULT_CONDITIONS.appendRefOrCommit()

    // prep SPARQL UPDATE string — only insert transaction + auto policy, NOT branch metadata
    val updateString = buildSparqlUpdate {
        insert {
            // create a new txn object in the transactions graph
            // store the commit source and source graph as intermediate txn properties
            txn(
                "mms-txn:commitSource" to "?__mms_commitSource",
                "mms-txn:sourceGraph" to "?sourceGraph",
            ) {
                // create a new policy that grants this user admin over the new branch
                autoPolicy(Scope.BRANCH, Role.ADMIN_BRANCH)
            }
        }
        where {
            // assert the required conditions (e.g., access-control, existence, etc.)
            raw(*localConditions.requiredPatterns())

            // attempt to locate the source graph for inclusion in the transaction results
            raw("""
                optional {
                    graph mor-graph:Metadata {
                        ?__mms_commitSource ^mms:commit/mms:snapshot ?snapshot .
                        ?snapshot mms:graph ?sourceGraph .
                    }
                }
            """)
        }
    }

    // execute update
    executeSparqlUpdate(updateString) {
        prefixes(prefixes)

        // replace IRI substitution variables
        iri(
            // user specified either ref or commit
            if(refSource != null) "_refSource" to refSource!!
            else "__mms_commitSource" to commitSource!!,
        )
    }

    // create construct query to confirm transaction (branch metadata not yet inserted)
    val constructString = buildSparqlQuery {
        construct {
            // all the details about this transaction
            txn()
        }
        where {
            // first group in a series of unions fetches intended outputs
            group {
                txn()
            }
            // all subsequent unions are for inspecting what if any conditions failed
            raw("""union ${localConditions.unionInspectPatterns()}""")
        }
    }

    // parameterizer setup shared by construct and later queries
    val sparqlSetup: SparqlParameterizer.() -> Unit = {
        prefixes(prefixes)

        // replace IRI substitution variables
        iri(
            // user specified either ref or commit
            if(refSource != null) "_refSource" to refSource!!
            else "__mms_commitSource" to commitSource!!,
        )
    }

    // execute construct to validate transaction
    val constructResponseText = executeSparqlConstructOrDescribe(constructString, sparqlSetup)

    // validate whether the transaction succeeded
    val constructModel = validateTransaction(constructResponseText, localConditions, null, null)

    // predetermine snapshot graphs
    val modelGraph = "${prefixes["mor-graph"]}Model.${transactionId}"
    val stagingGraph = "${prefixes["mor-graph"]}Staging.${transactionId}"

    // --- graph setup (before branch metadata) ---
    run {
        // isolate the transaction node
        val transactionNode = constructModel.createResource(prefixes["mt"])

        // snapshot is available for source commit
        val sourceGraphs = transactionNode.listProperties(MMS.TXN.sourceGraph).toList()
        if (sourceGraphs.size >= 1) {
            // copy existing snapshot graph to new branch's staging graph
            executeSparqlUpdate(
                """
                copy silent graph <${sourceGraphs[0].`object`.asResource().uri}> to graph ?_stgGraph ;
                
                insert data {
                    graph m-graph:Graphs {
                        ?_stgGraph a mms:SnapshotGraph .
                    }
                }
            """
            ) {
                prefixes(prefixes)
                iri(
                    "_stgGraph" to stagingGraph,
                )
            }
        }
        // no snapshots available, must build for commit
        else {
            // materialize the model graph for the commit
            val materializedGraph = materializeModelGraph(commitSource!!, modelGraph)

            // copy materialized graph to new branch's staging graph
            executeSparqlUpdate(
                """
                copy silent graph <$materializedGraph> to graph ?_stgGraph ;
                
                insert data {
                    graph m-graph:Graphs {
                        ?_stgGraph a mms:SnapshotGraph .
                    }
                }
            """
            ) {
                prefixes(prefixes)
                iri(
                    "_stgGraph" to stagingGraph,
                )
            }
        }
    }

    // --- insert branch metadata last (after graph setup) ---
    executeSparqlUpdate(
        """
        insert data {
            graph mor-graph:Metadata {
                $branchTriples
                
                morb: mms:commit ?_commitSource ;
                      mms:created ?_now ;
                      mms:snapshot ?_stgSnapshot .
                      
                ?_stgSnapshot a mms:Staging ;
                    mms:graph ?_stgGraph ;
                    .
            }
        }
    """
    ) {
        prefixes(prefixes)
        iri(
            "_commitSource" to (commitSource ?: run {
                // for ref-source, resolve the commit from the transaction
                val transactionNode = constructModel.createResource(prefixes["mt"])
                transactionNode.listProperties(MMS.TXN.commitSource).toList().firstOrNull()
                    ?.`object`?.asResource()?.uri
                    ?: throw Http500Excpetion("Transaction missing commit source")
            }),
            "_stgGraph" to stagingGraph,
            "_stgSnapshot" to "${prefixes["mor-snapshot"]}Staging.${transactionId}",
        )
        datatyped(
            "_now" to (Instant.now().toString() to XSDDatatype.XSDdateTime),
        )
    }

    // --- finalize: fetch branch triples for response ---
    val branchConstructString = buildSparqlQuery {
        construct {
            // all the details about this transaction
            txn()

            // all the properties about this branch
            raw("""
                morb: ?morb_p ?morb_o .
            """)
        }
        where {
            group {
                txn()

                raw("""
                    graph mor-graph:Metadata {
                        morb: ?morb_p ?morb_o .
                    }
                """)
            }
            raw("""union ${localConditions.unionInspectPatterns()}""")
        }
    }

    val branchConstructResponseText = executeSparqlConstructOrDescribe(branchConstructString, sparqlSetup)
    val branchModel = parseConstructResponse(branchConstructResponseText) {}

    // set response ETag from the created branch
    handleWrittenResourceEtag(branchModel, prefixes["morb"]!!)

    // respond with the created resource
    responseContext.createdResource(prefixes["morb"]!!, branchModel)

    // delete transaction
    run {
        val dropResponseText = executeSparqlUpdate("""
            delete where {
                graph m-graph:Transactions {
                    mt: ?p ?o .
                }
            }
        """)

        log.info("Delete transaction response:\n$dropResponseText")
    }
}

package org.openmbee.flexo.mms.routes.ldp

import io.ktor.http.*
import io.ktor.server.response.*
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.server.LdpDcLayer1Context
import org.openmbee.flexo.mms.server.LdpMutateResponse


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

    // prep SPARQL UPDATE string
    val updateString = buildSparqlUpdate {
        insert {
            // create a new txn object in the transactions graph
            txn(
                // add the source graph to the transaction results
                "mms-txn:sourceGraph" to "?sourceGraph",
            ) {
                // create a new policy that grants this user admin over the new branch
                autoPolicy(Scope.BRANCH, Role.ADMIN_BRANCH)
            }

            // insert the triples about the new branch, including arbitrary metadata supplied by user
            graph("mor-graph:Metadata") {
                raw("""
                    $branchTriples
                    # set branch pointer
                    morb: mms:commit ?__mms_commitSource ;
                          mms:created ?_now .
                """)
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

    // create construct query to confirm transaction and fetch repo details
    val constructString = buildSparqlQuery {
        construct {
            // all the details about this transaction
            txn()

            // all the properties about this branch
            raw("""
                morb: ?morb_p ?morb_o .
            """)
        }
        where {
            // first group in a series of unions fetches intended outputs
            group {
                txn()

                raw("""
                    graph mor-graph:Metadata {
                        morb: ?morb_p ?morb_o .
                    }
                """)
            }
            // all subsequent unions are for inspecting what if any conditions failed
            raw("""union ${localConditions.unionInspectPatterns()}""")
        }
    }

    // finalize transaction
    finalizeMutateTransaction(constructString, localConditions, "morb", true, {
        prefixes(prefixes)

        // replace IRI substitution variables
        iri(
            // user specified either ref or commit
            if(refSource != null) "_refSource" to refSource!!
            else "__mms_commitSource" to commitSource!!,
        )
    }) { constructModel ->
        // predetermine snapshot graphs
        val modelGraph = "${prefixes["mor-graph"]}Model.${transactionId}"
        val stagingGraph = "${prefixes["mor-graph"]}Staging.${transactionId}"
        val modelSnapshot = "${prefixes["mor-snapshot"]}Model.${transactionId}"

        // clone graph
        run {
            // isolate the transaction node
            val transactionNode = constructModel.createResource(prefixes["mt"])

            // snapshot is available for source commit
            val sourceGraphs = transactionNode.listProperties(MMS.TXN.sourceGraph).toList()
            if (sourceGraphs.size >= 1) {
                // copy graph
                executeSparqlUpdate(
                    """
                    # copy existing snapshot graph to new branch's staging graph
                    copy silent graph <${sourceGraphs[0].`object`.asResource().uri}> to graph ?_stgGraph ;
                    
                    # save snapshot metadata
                    insert data {
                        # add snapshot graph to registry
                        graph m-graph:Graphs {
                            ?_stgGraph a mms:SnapshotGraph .
                        }
                    
                        # declare snapshot in repository metadata
                        graph mor-graph:Metadata {
                            morb: mms:snapshot ?_stgSnapshot . 
                            ?_stgSnapshot a mms:Staging ;
                                mms:graph ?_stgGraph ;
                                .
                        }
                    }
                """
                ) {
                    prefixes(prefixes)
                    iri(
                        "_stgGraph" to stagingGraph,
                        "_stgSnapshot" to "${prefixes["mor-snapshot"]}Staging.${transactionId}",
                    )
                }
            }
            // no snapshots available, must build for commit
            else {
                // materialize the model graph for the commit
                val materializedGraph = materializeModelGraph(commitSource!!, modelGraph)

                // copy materialized graph to staging graph and save staging snapshot metadata
                executeSparqlUpdate(
                    """
                    # copy materialized model graph to new branch's staging graph
                    copy silent graph <$materializedGraph> to graph ?_stgGraph ;
                    
                    # save snapshot metadata
                    insert data {
                        # add snapshot graph to registry
                        graph m-graph:Graphs {
                            ?_stgGraph a mms:SnapshotGraph .
                        }
                    
                        # declare snapshot in repository metadata
                        graph mor-graph:Metadata {
                            morb: mms:snapshot ?_stgSnapshot . 
                            ?_stgSnapshot a mms:Staging ;
                                mms:graph ?_stgGraph ;
                                .
                        }
                    }
                """
                ) {
                    prefixes(prefixes)
                    iri(
                        "_stgGraph" to stagingGraph,
                        "_stgSnapshot" to "${prefixes["mor-snapshot"]}Staging.${transactionId}",
                    )
                }
            }
        }
    }
}

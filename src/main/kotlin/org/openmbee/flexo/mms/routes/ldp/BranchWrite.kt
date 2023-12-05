package org.openmbee.flexo.mms.routes.ldp

import com.linkedin.migz.MiGzInputStream
import io.ktor.http.*
import io.ktor.server.response.*
import org.apache.jena.rdf.model.ResourceFactory
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.*
import java.io.ByteArrayInputStream


private val ROOT_COMMIT_RESOURCE = ResourceFactory.createResource("urn:mms:rootCommit")

private val IS_PROPERTY = ResourceFactory.createProperty("urn:mms:is")


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

private val SPARQL_UPDATE_SEQUENCE = """
    insert {
        # copy origin graph
        graph ?_mdlGraph {
            ?origin_s ?origin_p ?origin_o .
        }
    
        graph m-graph:Graphs {
            ?_mdlGraph a mms:SnapshotGraph .
        }
    
        # save state for next queries
        graph m-graph:Transactions {
            mt:sequence
                mms-txn:originCommit ?originCommit ;
                mms-txn:originSnapshot ?originSnapshot ;
                mms-txn:originGraph ?originGraph ;
                .
        }
    }
    where {
        graph mor-graph:Metadata {
            # commit's ancestors
            ?_commitSource a mms:Commit ;
                mms:parent+ ?originCommit .
    
            # pick the closest commit which also has a ref by excluding 'heirs'
            filter not exists {
                ?_commitSource mms:parent+ ?heirCommit .
    
                ?heirCommit mms:parent+ ?originCommit .
    
                ?heirRef mms:commit ?heirCommit .
            }
    
            # traverse to snapshot
            ?originRef mms:commit ?originCommit ;
                mms:snapshot ?originSnapshot .
    
            # resolve to origin graph
            ?originSnapshot mms:graph ?originGraph .
        }
    
    
        # select contents of origin graph
        optional {
            graph ?originGraph {
                ?origin_s ?origin_p ?origin_o .
            }
        }
    }
""".trimIndent()

private val SPARQL_CONSTRUCT_DELTAS = """
    construct {
        ?deltaCommit ?delta_p ?delta_o .
    
        ?deltaData ?data_p ?data_o .
        
        <${ROOT_COMMIT_RESOURCE.uri}> <${IS_PROPERTY.uri}> ?rootCommit .
    } where {
        # restore state from previous transaction
        graph m-graph:Transactions {
            mt:sequence
                mms-txn:originCommit ?originCommit ;
                mms-txn:originSnapshot ?originSnapshot ;
                mms-txn:originGraph ?originGraph ;
                .
        }
    
        graph mor-graph:Metadata {
            # select all prior delta commits...
            ?_commitSource mms:parent* ?deltaCommit .
    
            # ...that occur after the origin commit
            ?deltaCommit mms:parent+ ?originCommit ;
                mms:data ?deltaData ;
                ?delta_p ?delta_o .
    
            # all delta commit data
            ?deltaData ?data_p ?data_o.
            
            # 'root' commit
            ?rootCommit mms:parent ?originCommit .
        }
    }
""".trimIndent()


suspend fun AnyLayer1Context.createBranch() {
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
                    morb: mms:commit ?__mms_commitSource .
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
                txn(null, "morb")

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

    // execute construct
    val constructResponseText = executeSparqlConstructOrDescribe(constructString) {
        prefixes(prefixes)

        // replace IRI substitution variables
        iri(
            // user specified either ref or commit
            if(refSource != null) "_refSource" to refSource!!
            else "__mms_commitSource" to commitSource!!,
        )
    }

    // validate whether the transaction succeeded
    val constructModel = validateTransaction(constructResponseText, localConditions, null, "morb")

    // check that the user-supplied HTTP preconditions were met
    handleEtagAndPreconditions(constructModel, prefixes["morb"])

    // respond
    call.respondText(constructResponseText, RdfContentTypes.Turtle)


    //
    // ==== Response closed ====
    //


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
        if(sourceGraphs.size >= 1) {
            // copy graph
            executeSparqlUpdate("""
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
            """) {
                iri(
                    "_stgGraph" to stagingGraph,
                    "_stgSnapshot" to "${prefixes["mor-snapshot"]}Staging.${transactionId}",
                )
            }
        }
        // no snapshots available, must build for commit
        else {
            // select most recent preceding snapshot and copy to new snapshot graph
            val sequenceUpdateResponseText = executeSparqlUpdate(SPARQL_UPDATE_SEQUENCE) {
                prefixes(prefixes)

                iri(
                    "_commitSource" to commitSource!!,
                    "_mdlGraph" to modelGraph,
                )
            }

            log.info(sequenceUpdateResponseText)

            // select all commits between source and target
            val deltasResponseText = executeSparqlConstructOrDescribe(SPARQL_CONSTRUCT_DELTAS) {
                prefixes(prefixes)

                iri(
                    "_commitSource" to commitSource!!,
                )
            }

            log.info(deltasResponseText)

            // build sparql update string
            val updates = mutableListOf<String>()
            parseConstructResponse(deltasResponseText) {
                // find root commit
                val rootCommits = model.listObjectsOfProperty(ROOT_COMMIT_RESOURCE, IS_PROPERTY).toList()
                if(rootCommits.size != 1) throw Http500Excpetion("Failed to determine commit history")

                // set initial commit resource
                var commit = rootCommits[0].asResource()

                // iterate thru all commits
                while(true) {
                    // get patch body
                    val patches = model.listObjectsOfProperty(commit.extractExactly1Uri(MMS.data), MMS.patch).toList()
                    if(patches.size != 1) throw Http500Excpetion("Commit data missing patch string")

                    // ref literal
                    val patchLiteral = patches[0].asLiteral()

                    // compressed sparql gz
                    val patchString = if(patchLiteral.datatype == MMS_DATATYPE.sparqlGz) {
                        val bytes = patchLiteral.string.toByteArray()

                        // prep input stream
                        val stream = ByteArrayInputStream(bytes)

                        // instantiate decompressor
                        val migz = MiGzInputStream(stream, Runtime.getRuntime().availableProcessors())

                        // read decompressed data and create string
                        String(migz.readAllBytes())

                    }
                    // uncompressed sparql
                    else {
                        patchLiteral.string
                    }

                    // add to update strings
                    updates.add(patchString)

                    // traverse to child commit
                    val children = model.listSubjectsWithProperty(MMS.parent, commit).toList()
                    if(children.isEmpty()) break

                    // repeat
                    commit = children[0]
                }
            }

            // apply all commits in sequence
            executeSparqlUpdate(updates.joinToString(" ;\n")) {
                prefixes(prefixes)

                iri(
                    "__mms_model" to modelGraph
                )
            }

            // save new snapshot
            executeSparqlUpdate("""
                insert data {
                    graph mor-graph:Metadata {
                        mor-lock:Commit.${transactionId} a mms:Lock ;
                            mms:commit morc: ;
                            mms:snapshot ?_mdlSnapshot ;
                            .

                        ?_mdlSnapshot a mms:Model ;
                            mms:graph ?_mdlGraph ;
                            .
                    }
                }
            """) {
                iri(
                    "_mdlGraph" to modelGraph,
                    "_mdlSnapshot" to modelSnapshot,
                )
            }
        }
    }

    // delete transaction
    run {
        // submit update
        val dropResponseText = executeSparqlUpdate("""
            delete where {
                graph m-graph:Transactions {
                    mt: ?p ?o .
                }
            }
        """)

        // log response
        log.info(dropResponseText)
    }
}

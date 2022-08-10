package org.openmbee.mms5.routes

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.mms5.*


// default starting conditions for any calls to create a branch
private val DEFAULT_CONDITIONS = REPO_CRUD_CONDITIONS.append {
    // require that the user has the ability to create branches on a repo-level scope
    permit(Permission.CREATE_BRANCH, Scope.REPO)

    // require that the given branch does not exist before attempting to create it
    require("branchNotExists") {
        handler = { mms -> "The provided branch <${mms.prefixes["morb"]}> already exists." }

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


fun Route.createBranch() {
    put("/orgs/{orgId}/repos/{repoId}/branches/{branchId}") {
        call.mmsL1(Permission.CREATE_BRANCH) {
            // parse the path params
            pathParams {
                org()
                repo()
                branch(legal=true)
            }

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
                            
                            # reference the commit source
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

            // clone graph
            run {
                // isolate the transaction node
                val transactionNode = constructModel.createResource(prefixes["mt"])

                // snapshot is available for source commit
                val sourceGraphs = transactionNode.listProperties(MMS.TXN.sourceGraph).toList()
                if(sourceGraphs.size >= 1) {
                    // copy graph
                    executeSparqlUpdate("""
                        copy silent graph <${sourceGraphs[0].`object`.asResource().uri}> to graph ?_stgGraph ;
                        
                        insert data {
                            graph m:Graphs {
                                ?_stgGraph a mms:SnapshotGraph .
                            }
                        
                            graph mor-graph:Metadata {
                                morb: mms:snapshot ?_stgSnapshot . 
                                ?_stgSnapshot a mms:Staging ;
                                    mms:graph ?_stgGraph ;
                                    .
                            }
                        }
                    """) {
                        iri(
                            "_stgGraph" to "${prefixes["mor-graph"]}Staging.${transactionId}",
                            "_stgSnapshot" to "${prefixes["mor-snapshot"]}Staging.${transactionId}",
                        )
                    }

                    // copy staging => model
                    executeSparqlUpdate("""
                        copy silent graph ?_stgGraph to ?_mdlGraph ;
                        
                        insert data {
                            graph m:Graphs {
                                ?_mdlGraph a mms:SnapshotGraph .
                            }

                            graph mor-graph:Metadata {
                                morb: mms:snapshot ?_mdlSnapshot .
                                ?_mdlSnapshot a mms:Model ;
                                    mms:graph ?_mdlGraph ;
                                    .
                            }
                        }
                    """) {
                        iri(
                            "_stgGraph" to "${prefixes["mor-graph"]}Staging.${transactionId}",
                            "_mdlGraph" to "${prefixes["mor-graph"]}Model.${transactionId}",
                            "_mdlSnapshot" to "${prefixes["mor-snapshot"]}Model.${transactionId}",
                        )
                    }
                }
                // no snapshots available, must build for commit
                else {
                    TODO("build snapshot")
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
    }
}
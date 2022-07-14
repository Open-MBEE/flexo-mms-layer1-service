package org.openmbee.mms5.routes

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
import kotlinx.coroutines.selects.select
import org.apache.jena.rdf.model.Property
import org.apache.jena.rdf.model.Resource
import org.apache.jena.vocabulary.RDF
import org.openmbee.mms5.*


// default starting conditions for any calls to create a lock
private val DEFAULT_CONDITIONS = REPO_CRUD_CONDITIONS.append {
    // require that the user has the ability to create locks on a repo-level scope
    permit(Permission.CREATE_LOCK, Scope.REPO)

    // require that the given lock does not exist before attempting to create it
    require("lockNotExists") {
        handler = { mms -> "The provided lock <${mms.prefixes["morl"]}> already exists." }

        """
            # lock must not yet exist
            graph mor-graph:Metadata {
                filter not exists {
                    morl: a mms:Lock .
                }
            }
        """
    }
}

private const val SPARQL_CONSTRUCT_SNAPSHOT = """
    construct {
        ?baseSnapshot mms:graph ?baseGraph .
        ?baseRef
            mms:commit ?baseCommit ;
            mms:snapshot ?baseSnapshot .

        ?ancestor mms:parent ?parent ;
            mms:patch ?patch ;
            mms:where ?where .
    } where {
        graph mor-graph:Metadata {
            # locate a commit that...
            ?__mms_commit mms:parent* ?baseCommit .

            # ... is targeted by some ref
            ?baseRef mms:commit ?baseCommit ;
                # access its snapshot
                mms:snapshot ?baseSnapshot .

            # and that snapshot's graph
            ?baseSnapshot mms:graph ?baseGraph .

            # only match the most recent snapshot in the commit history
            filter not exists {
                morc: mms:parent* ?newerCommit .
                ?newerCommit mms:parent* ?baseCommit ;
                    ^mms:commit/mms:snapshot ?newerSnapshot .

                filter(?newerCommit != ?baseCommit)
            }


            # fetch the ancestry between the base and target commits
            optional {
                ?__mms_commit mms:parent* ?ancestor .
                ?ancestor mms:parent ?parent ;
                    mms:data ?data .
                ?data mms:patch ?patch ;
                    mms:where ?where .
                
                # exclude commits older than the base commit
                filter not exists {
                    ?baseCommit mms:parent* ?ancestor .
                }
            }
        }
    }
"""


fun Route.createLock() {
    put("/orgs/{orgId}/repos/{repoId}/locks/{lockId}") {
        call.mmsL1(Permission.CREATE_LOCK) {
            // parse the path params
            pathParams {
                org()
                repo()
                lock(legal = true)
            }

            // process RDF body from user about this new lock
            val lockTriples = filterIncomingStatements("morl") {
                // relative to this lock node
                lockNode().apply {
                    // assert and normalize ref/commit triples
                    normalizeRefOrCommit(this)

                    // sanitize statements
                    sanitizeCrudObject {
                        setProperty(RDF.type, MMS.Lock)
                        setProperty(MMS.id, lockId!!)
                        setProperty(MMS.etag, transactionId, true)
                        setProperty(MMS.commit, commitNode())
                        setProperty(MMS.createdBy, userNode(), true)
                    }
                }
            }

            // //
            // executeSparqlSelectOrAsk("""
            //     select ?_refSource ?__mms_commitSource {
            //         ${conditions {}.appendRefOrCommit().requiredPatterns().joinToString("\n")}
            //     }
            // """)

            // extend the default conditions with requirements for user-specified ref or commit
            val localConditions = DEFAULT_CONDITIONS.appendRefOrCommit()

            //
            // // prep SPARQL UPDATE string
            // val prelockUpdateString = buildSparqlUpdate {
            //     insert {
            //         // create a new subtxn object in the transactions graph
            //         subtxn("1", mapOf(
            //             "mms-txn:commitSource" to "?__mms_commitSource",
            //             "mms-txn:refSource" to "?_refSource",
            //             "mms-txn:baseGraph" to "?baseGraph",
            //             "mms-txn:baseRef" to "?baseRef",
            //             "mms-txn:baseCommit" to "?baseCommit",
            //             "mms-txn:baseSnapshot" to "?baseSnapshot",
            //             "mms-txn:ancestor" to "?ancestor",
            //             "mms-txn:parent" to "?parent",
            //             "mms-txn:patch" to "?patch",
            //             "mms-txn:where" to "?where",
            //         ))
            //     }
            //     where {
            //         raw("""
            //             graph mor-graph:Metadata {
            //                 # locate a commit that...
            //                 ?__mms_commit mms:parent* ?baseCommit .
            //
            //                 # ... is targeted by some ref
            //                 ?baseRef mms:commit ?baseCommit ;
            //                     # access its snapshot
            //                     mms:snapshot ?baseSnapshot .
            //
            //                 # and that snapshot's graph
            //                 ?baseSnapshot mms:graph ?baseGraph .
            //
            //                 # only match the most recent snapshot in the commit history
            //                 filter not exists {
            //                     morc: mms:parent* ?newerCommit .
            //                     ?newerCommit mms:parent* ?baseCommit ;
            //                         ^mms:commit/mms:snapshot ?newerSnapshot .
            //
            //                     filter(?newerCommit != ?baseCommit)
            //                 }
            //
            //
            //                 # fetch the ancestry between the base and target commits
            //                 optional {
            //                     ?__mms_commit mms:parent* ?ancestor .
            //                     ?ancestor mms:parent ?parent ;
            //                         mms:data ?data .
            //                     ?data mms:patch ?patch ;
            //                         mms:where ?where .
            //
            //                     # exclude commits older than the base commit
            //                     filter not exists {
            //                         ?baseCommit mms:parent* ?ancestor .
            //                     }
            //                 }
            //             }
            //
            //             # assert the required conditions (e.g., access-control, existence, etc.)
            //             ${localConditions.requiredPatterns().joinToString("\n")}
            //         """)
            //     }
            // }
            //
            //
            // log.info(prelockUpdateString)
            //
            // // execute update
            // executeSparqlUpdate(prelockUpdateString) {
            //     prefixes(prefixes)
            // }
            //
            // // create construct query to confirm transaction and fetch lock details
            // val validatePrelockConstructString = buildSparqlQuery {
            //     construct  {
            //         // all the details about this transaction
            //         txn("1")
            //     }
            //     where {
            //         // first group in a series of unions fetches intended outputs
            //         group {
            //             txn("1")
            //         }
            //         // all subsequent unions are for inspecting what if any conditions failed
            //         raw("""union ${localConditions.unionInspectPatterns()}""")
            //     }
            // }
            //
            // log.info(validatePrelockConstructString)
            //
            //
            // // execute construct
            // val validatePrelockResponseText = executeSparqlConstructOrDescribe(validatePrelockConstructString)
            //
            // // validate whether the transaction succeeded
            // val validatePrelockResponseModel = validateTransaction(validatePrelockResponseText, localConditions, "1")



            // // locate base snapshot
            // val constructSnapshotResponseText = executeSparqlConstructOrDescribe(SPARQL_CONSTRUCT_SNAPSHOT)
            //
            // log.info(constructSnapshotResponseText)
            //
            // // base snapshot graph
            // var baseSnapshotGraphUri: String? = null
            //
            // // sequence of commits from base to target
            // val commitSequenceUris = mutableListOf<String>()
            // val patchWhereBodies = hashMapOf<String, Pair<String, String>>()
            //
            // // parse the response graph
            // parseConstructResponse(constructSnapshotResponseText) {
            //     // initialize to target commit in case target snapshot already exists
            //     var baseCommit = commitNode()
            //
            //     // prep accessor function
            //     val literalStringValueAt = { res: Resource, prop: Property -> model.listObjectsOfProperty(res, prop).next().asLiteral().string }
            //
            //     // traverse up the ancestry chain
            //     var parents = model.listObjectsOfProperty(baseCommit, MMS.parent)
            //     while(parents.hasNext()) {
            //         // save patch/where literal value pair
            //         patchWhereBodies[baseCommit.uri] = literalStringValueAt(baseCommit, MMS.patch) to literalStringValueAt(baseCommit, MMS.where)
            //
            //         // key by commit uri
            //         commitSequenceUris.add(0, baseCommit.uri)
            //
            //         // iterate
            //         baseCommit = parents.next().asResource()
            //         parents = model.listObjectsOfProperty(baseCommit, MMS.parent)
            //     }
            //
            //     // traverse ref to graph
            //     val superRef = model.listResourcesWithProperty(MMS.commit, baseCommit).next()
            //
            //     // baseRefUris.addAll((super))
            //     model.listObjectsOfProperty(superRef, MMS.snapshot).next().asResource().listProperties(MMS.graph).next().let {
            //         baseSnapshotGraphUri = it.`object`.asResource().uri
            //     }
            // }

            // log.info("base snapshot graph: <$baseSnapshotGraphUri>")
            // log.info(commitSequenceUris.joinToString(", ") { "<$it> :: ${patchWhereBodies[it]!!.first}" })


            // only support lock on existing ref for now
            if(refSource == null) {
                throw Http500Excpetion("Creating Locks on commits is not yet supported")
            }

            // prep SPARQL UPDATE string
            val updateString = buildSparqlUpdate {
                // first, copy the base snapshot graph to the new lock model graph
                insert {
                    graph("?__mms_model") {
                        raw("?s ?p ?o");
                    }

                    graph("m-graph:Graphs") {
                        raw("""
                            ?__mms_model a mms:ModelGraph .
                        """)
                    }
                }
                where {
                    // raw(*refSourceCondition.requiredPatterns())
                    raw("""
                        graph m-graph:Schema {
                            ?__mms_refSourceClass rdfs:subClassOf* mms:Ref .
                        }
                    
                        graph mor-graph:Metadata {         
                            ?_refSource a ?__mms_refSourceClass ;
                                mms:commit ?__mms_commitSource ;
                                mms:snapshot ?baseSnapshot .

                            # and that snapshot's graph
                            ?baseSnapshot mms:graph ?baseGraph .
                
                            # only use the model snapshot
                            ?baseSnapshot a mms:Model .
                        }
                        
                        optional {
                            graph ?baseGraph {
                                ?s ?p ?o
                            }
                        }
                    """)
                }

                // // first, copy the base snapshot graph to the new lock model graph
                // raw("""
                //     copy graph <$baseSnapshotGraphUri> to graph ?__mms_model ;
                // """)

                // // rebuilding snapshot does not require checking update conditions...
                // raw(commitSequenceUris.map {
                //     // ... so just use the patch string
                //     "${patchWhereBodies[it]!!.first} ; "
                // }.joinToString(""))

                insert {
                    // create a new txn object in the transactions graph
                    txn {
                        // create a new policy that grants this user admin over the new lock
                        autoPolicy(Scope.LOCK, Role.ADMIN_LOCK)
                    }

                    // insert the triples about the new lock, including arbitrary metadata supplied by user
                    graph("mor-graph:Metadata") {
                        raw(lockTriples)
                    }
                }
                where {
                    // assert the required conditions (e.g., access-control, existence, etc.)
                    raw(*localConditions.requiredPatterns())
                }
            }

            log.info(updateString)

            // execute update
            executeSparqlUpdate(updateString) {
                prefixes(prefixes)

                // replace IRI substitution variables
                iri(
                    "__mms_model" to "${prefixes["mor-graph"]}Model.${transactionId}",
                    "_refSource" to refSource!!
                )
            }

            // create construct query to confirm transaction and fetch lock details
            val constructString = buildSparqlQuery {
                construct  {
                    // all the details about this transaction
                    txn()

                    // all the properties about this lock
                    raw("""
                        morl: ?morl_p ?morl_o .
                    """)
                }
                where {
                    // first group in a series of unions fetches intended outputs
                    group {
                        txn(null, "morl")

                        raw("""
                            graph mor-graph:Metadata {
                                morl: ?morl_p ?morl_o .
                            }
                        """)
                    }
                    // all subsequent unions are for inspecting what if any conditions failed
                    raw("""union ${localConditions.unionInspectPatterns()}""")
                }
            }

            // execute construct
            val constructResponseText = executeSparqlConstructOrDescribe(constructString)

            // validate whether the transaction succeeded
            val constructModel = validateTransaction(constructResponseText, localConditions)

            // check that the user-supplied HTTP preconditions were met
            handleEtagAndPreconditions(constructModel, prefixes["morl"])

            // respond
            call.respondText(constructResponseText, RdfContentTypes.Turtle)

            // log
            log.info("Triplestore responded with:\n$constructResponseText")

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
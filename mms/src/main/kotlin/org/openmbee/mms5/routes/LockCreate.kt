package org.openmbee.mms5.routes

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
import org.apache.jena.rdf.model.Property
import org.apache.jena.rdf.model.Resource
import org.apache.jena.vocabulary.RDF
import org.openmbee.mms5.*


private val DEFAULT_CONDITIONS = COMMIT_CRUD_CONDITIONS.append {
    permit(Permission.CREATE_LOCK, Scope.REPO)

    require("lockNotExists") {
        handler = { mms -> "The provided lock <${mms.prefixes["morcl"]}> already exists." }

        """
            # lock must not yet exist
            graph m-graph:Cluster {
                filter not exists {
                    morcl: a mms:Lock .
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
            morc: mms:parent* ?baseCommit .

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
                morc: mms:parent* ?ancestor .
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


fun Application.createLock() {
    routing {
        put("/orgs/{orgId}/repos/{repoId}/commits/{commitId}/locks/{lockId}") {
            call.mmsL1(Permission.CREATE_LOCK) {
                pathParams {
                    org()
                    repo()
                    commit()
                    lock(legal = true)
                }

                val lockTriples = filterIncomingStatements("morcl") {
                    lockNode().apply {
                        sanitizeCrudObject {
                            setProperty(RDF.type, MMS.Lock)
                            setProperty(MMS.id, lockId!!)
                            setProperty(MMS.etag, transactionId, true)
                            setProperty(MMS.commit, commitNode())
                            setProperty(MMS.createdBy, userNode(), true)
                        }
                    }
                }

                val localConditions = DEFAULT_CONDITIONS

                // locate base snapshot
                val constructSnapshotResponseText = executeSparqlConstructOrDescribe(SPARQL_CONSTRUCT_SNAPSHOT)

                log.info(constructSnapshotResponseText)

                // base snapshot graph
                var baseSnapshotGraphUri: String? = null

                // sequence of commits from base to target
                val commitSequenceUris = mutableListOf<String>()
                val patchWhereBodies = hashMapOf<String, Pair<String, String>>()

                // parse the response graph
                parseConstructResponse(constructSnapshotResponseText) {
                    // initialize to target commit in case target snapshot already exists
                    var baseCommit = commitNode()

                    val literalStringValueAt = { res: Resource, prop: Property -> model.listObjectsOfProperty(res, prop).next().asLiteral().string }

                    // traverse up the ancestry chain
                    var parents = model.listObjectsOfProperty(baseCommit, MMS.parent)
                    while(parents.hasNext()) {
                        // save patch/where literal value pair
                        patchWhereBodies[baseCommit.uri] = literalStringValueAt(baseCommit, MMS.patch) to literalStringValueAt(baseCommit, MMS.where)

                        // key by commit uri
                        commitSequenceUris.add(0, baseCommit.uri)

                        baseCommit = parents.next().asResource()
                        parents = model.listObjectsOfProperty(baseCommit, MMS.parent)
                    }

                    // traverse ref to graph
                    val superRef = model.listResourcesWithProperty(MMS.commit, baseCommit).next()

                    // baseRefUris.addAll((super))
                    model.listObjectsOfProperty(superRef, MMS.snapshot).next().asResource().listProperties(MMS.graph).next().let {
                        baseSnapshotGraphUri = it.`object`.asResource().uri
                    }
                }

                // log.info("base snapshot graph: <$baseSnapshotGraphUri>")
                // log.info(commitSequenceUris.joinToString(", ") { "<$it> :: ${patchWhereBodies[it]!!.first}" })

                val updateString = buildSparqlUpdate {
                    raw("""
                        copy <$baseSnapshotGraphUri> to ?__mms_model ; 
                    """)

                    // rebuilding snapshot does not require checking update conditions...
                    raw(commitSequenceUris.map {
                        // ... so just use the patch string
                        "${patchWhereBodies[it]!!.first} ; "
                    }.joinToString(""))

                    insert {
                        txn {
                            autoPolicy(Scope.LOCK, Role.ADMIN_LOCK)
                        }

                        graph("mor-graph:Metadata") {
                            raw(lockTriples)
                        }
                    }
                    where {
                        raw(*localConditions.requiredPatterns())
                        groupDns()
                    }
                }

                log.info(updateString)

                executeSparqlUpdate(updateString) {
                    iri(
                        "__mms_model" to "${prefixes["mor-graph"]}Model.${transactionId}"
                    )
                }


                // create construct query to confirm transaction and fetch project details
                val constructString = buildSparqlQuery {
                    construct  {
                        txn()

                        raw("""
                            morcl: ?morcl_p ?morcl_o .
                        """)
                    }
                    where {
                        group {
                            txn()

                            raw("""
                                graph mor-graph:Metadata {
                                    morcl: ?morcl_p ?morcl_o .
                                }
                            """)
                        }
                        raw("""union ${localConditions.unionInspectPatterns()}""")
                        groupDns()
                    }
                }

                val constructResponseText = executeSparqlConstructOrDescribe(constructString)

                validateTransaction(constructResponseText, localConditions)

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
}
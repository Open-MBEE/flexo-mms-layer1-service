package org.openmbee.mms5.routes

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
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

            // extend the default conditions with requirements for user-specified ref or commit
            val localConditions = DEFAULT_CONDITIONS.appendRefOrCommit()

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

                // prep accessor function
                val literalStringValueAt = { res: Resource, prop: Property -> model.listObjectsOfProperty(res, prop).next().asLiteral().string }

                // traverse up the ancestry chain
                var parents = model.listObjectsOfProperty(baseCommit, MMS.parent)
                while(parents.hasNext()) {
                    // save patch/where literal value pair
                    patchWhereBodies[baseCommit.uri] = literalStringValueAt(baseCommit, MMS.patch) to literalStringValueAt(baseCommit, MMS.where)

                    // key by commit uri
                    commitSequenceUris.add(0, baseCommit.uri)

                    // iterate
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

            // prep SPARQL UPDATE string
            val updateString = buildSparqlUpdate {
                // first, copy the base snapshot graph to the new lock model graph
                raw("""
                    copy graph <$baseSnapshotGraphUri> to graph ?__mms_model ; 
                """)

                // rebuilding snapshot does not require checking update conditions...
                raw(commitSequenceUris.map {
                    // ... so just use the patch string
                    "${patchWhereBodies[it]!!.first} ; "
                }.joinToString(""))

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
                    "__mms_model" to "${prefixes["mor-graph"]}Model.${transactionId}"
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
                        txn()

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
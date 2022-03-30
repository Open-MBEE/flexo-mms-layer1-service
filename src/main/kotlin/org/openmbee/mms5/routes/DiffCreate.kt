package org.openmbee.mms5.routes

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
import org.openmbee.mms5.*
import java.security.MessageDigest


private val DEFAULT_CONDITIONS = COMMIT_CRUD_CONDITIONS.append {
    permit(Permission.CREATE_BRANCH, Scope.REPO)

    require("branchNotExists") {
        handler = { mms -> "The provided branch <${mms.prefixes["morb"]}> already exists." }

        """
            # branch must not yet exist
            graph m-graph:Cluster {
                filter not exists {
                    morb: a mms:Branch .
                }
            }
        """
    }
}

private fun hashString(input: String, algorithm: String): String {
    return MessageDigest
        .getInstance(algorithm)
        .digest(input.toByteArray())
        .fold("", { str, it -> str + "%02x".format(it) })
}


fun Route.createDiff() {
    post("/orgs/{orgId}/repos/{repoId}/diff") {
        call.mmsL1(Permission.CREATE_DIFF) {
            pathParams {
                org()
                repo()
                commit()
                lock()
            }

            // diffId = transactionId

            lateinit var srcRef: String
            lateinit var dstRef: String

            var createDiffUserDataTriples = ""
            filterIncomingStatements("mor") {
                diffNode().apply {
                    srcRef = extractExactly1Uri(MMS.srcRef).uri
                    dstRef = extractExactly1Uri(MMS.dstRef).uri

                    sanitizeCrudObject {
                        removeAll(MMS.srcRef)
                        removeAll(MMS.dstRef)
                    }
                }

                val diffPairs = serializePairs(diffNode())
                if(diffPairs.isNotEmpty()) {
                    createDiffUserDataTriples = "?diff $diffPairs ."
                }

                diffNode()
            }

            val localConditions = DEFAULT_CONDITIONS.appendSrcRef().appendDstRef()

            // generate sparql update
            val updateString = genDiffUpdate(createDiffUserDataTriples, localConditions, """
                graph mor-graph:Metadata {
                    # select the latest commit from the current named ref
                    ?srcRef mms:commit ?srcCommit .
                    
                    ?srcCommit ^mms:commit/mms:snapshot ?srcSnapshot .
                    
                    ?srcSnapshot a mms:Model ; 
                        mms:graph ?srcGraph  .
                    
                    
                    ?dstRef mms:commit ?dstCommit .
                    
                    ?dstCommit ^mms:commit/mms:snapshot ?dstSnapshot .
                    
                    ?dstSnapshot a mms:Model ; 
                        mms:graph ?dstGraph .
                }
            """)

            executeSparqlUpdate(updateString) {
                iri(
                    "srcRef" to srcRef,
                    "dstRef" to dstRef,
                )
            }

            val constructString = buildSparqlQuery {
                construct {
                    txn()
                }
                where {
                    group {
                        txn()
                    }
                    raw("""
                        union ${localConditions.unionInspectPatterns()}    
                    """)
                }
            }

            val constructResponseText = executeSparqlConstructOrDescribe(constructString)

            val constructModel = validateTransaction(constructResponseText, localConditions)


            // clone graph
            run {
                val transactionNode = constructModel.createResource(prefixes["mt"])

                // snapshot is available for source commit
                val sourceGraphs = transactionNode.listProperties(MMS.TXN.sourceGraph).toList()
                if(sourceGraphs.size >= 1) {
                    // copy graph
                    executeSparqlUpdate("""
                        copy graph <${sourceGraphs[0].`object`.asResource().uri}> to mor-graph:Staging.${transactionId} ;
                        
                        insert {
                            morb: mms:snapshot ?snapshot .
                            mor-snapshot:Staging.${transactionId} a mms:Staging ;
                                mms:graph mor-graph:Staging.${transactionId} ;
                                .
                        }
                    """)

                    // copy staging => model
                    executeSparqlUpdate("""
                        copy mor-snapshot:Staging.${transactionId} mor-snapshot:Model.${transactionId} ;
                        
                        insert {
                            morb: mms:snapshot mor-snapshot:Model.${transactionId} .
                            mor-snapshot:Model.${transactionId} a mms:Model ;
                                mms:graph mor-graph:Model.${transactionId} ;
                                .
                        }
                    """)
                }
                // no snapshots available, must build for commit
                else {
                    TODO("build snapshot")
                }
            }

            call.respondText(constructResponseText, RdfContentTypes.Turtle)

            // delete transaction
            run {
                // submit update
                val dropResponseText = executeSparqlUpdate("""
                    delete where {
                        graph m-graph:Transactions {
                            mt: ?p ?o .
                        }
                    }
                """) {
                    prefixes(prefixes)
                }

                // log response
                log.info(dropResponseText)
            }
        }
    }
}
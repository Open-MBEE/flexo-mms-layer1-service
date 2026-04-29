package org.openmbee.flexo.mms.routes.ldp

import io.ktor.server.response.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.server.LdpDcLayer1Context
import org.openmbee.flexo.mms.server.LdpDeleteResponse


suspend fun LdpDcLayer1Context<LdpDeleteResponse>.deleteLock() {
    // Save model graph IRIs before deletion (metadata will be removed)
    val modelGraphQuery = """
        select ?modelGraph where {
            graph mor-graph:Metadata {
                morl: mms:snapshot ?snapshot .
                ?snapshot mms:graph ?modelGraph .
            }
        }
    """

    val modelGraphResults = executeSparqlSelectOrAsk(modelGraphQuery) {
        prefixes(prefixes)
    }
    val candidateGraphIris = parseSparqlResultsJsonSelect(modelGraphResults).mapNotNull { binding ->
        binding["modelGraph"]?.jsonObject?.get("value")?.jsonPrimitive?.content
    }

    // Execute the delete
    val localConditions = LOCK_DELETE_CONDITIONS

    val updateString = buildSparqlUpdate {
        delete {
            graph("mor-graph:Metadata") {
                raw("""
                    morl: ?morl_p ?morl_o .
                """)
            }
            graph("mor-graph:Metadata") {
                raw("""
                    ?snapshot ?snapshot_p ?snapshot_o .
                """)
            }
            graph("m-graph:AccessControl.Policies") {
                raw("""
                    ?lockPolicy ?lockPolicy_p ?lockPolicy_o .
                """)
            }
        }
        insert {
            txn()
            graph("m-graph:Transactions") {
                raw("""
                    mt: mms-txn:droppedObject morl: .
                """)
            }
        }
        where {
            raw("""
                graph mor-graph:Metadata {
                    morl: ?morl_p ?morl_o .
                    
                    optional {
                        morl: mms:snapshot ?snapshot .
                        ?snapshot ?snapshot_p ?snapshot_o .
                        
                        filter not exists {
                            ?otherLock mms:snapshot ?snapshot .
                            filter(?otherLock != morl:)
                        }
                    }
                }
                
                optional {
                    graph m-graph:AccessControl.Policies {
                        ?lockPolicy mms:scope morl: ;
                            ?lockPolicy_p ?lockPolicy_o .
                    }
                }
            """)
            raw(*localConditions.requiredPatterns())
        }
    }

    log.info(updateString)
    executeSparqlUpdate(updateString)

    // Validate the transaction
    val constructString = buildSparqlQuery {
        construct {
            txn()
        }
        where {
            txnOrInspections(null, localConditions) {}
        }
    }

    val constructResponseText = executeSparqlConstructOrDescribe(constructString)
    validateTransaction(constructResponseText, localConditions, null, "morl")

    // Respond with the transaction result
    call.respondText(constructResponseText, contentType = RdfContentTypes.Turtle)

    // Clean up - drop model graphs no longer referenced by any snapshot
    for (graphIri in candidateGraphIris) {
        val stillReferencedQuery = """
            ask where {
                graph mor-graph:Metadata {
                    ?snapshot mms:graph <$graphIri> .
                }
            }
        """
        val stillReferenced = parseSparqlResultsJsonAsk(
            executeSparqlSelectOrAsk(stillReferencedQuery) { prefixes(prefixes) }
        )
        if (!stillReferenced) {
            executeSparqlUpdate("drop silent graph <$graphIri>")
        }
    }

    executeSparqlUpdate("""
        delete where {
            graph m-graph:Transactions {
                mt: ?p ?o .
            }
        }
    """)
}

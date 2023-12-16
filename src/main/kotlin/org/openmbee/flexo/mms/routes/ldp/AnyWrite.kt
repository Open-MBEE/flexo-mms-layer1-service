package org.openmbee.flexo.mms.routes.ldp

import org.openmbee.flexo.mms.ConditionsGroup
import org.openmbee.flexo.mms.server.LdpDcLayer1Context
import org.openmbee.flexo.mms.server.LdpWriteResponse

suspend fun <TResponseContext: LdpWriteResponse> LdpDcLayer1Context<TResponseContext>.finalizeWriteTransaction(
    constructString: String,
    localConditions: ConditionsGroup,
    resourceSymbol: String
) {
    // execute construct
    val constructResponseText = executeSparqlConstructOrDescribe(constructString)

    // log
    log.info("Finalizing write transaction...\n######## request: ########\n$constructString\n\n######## response: ########\n$constructResponseText")

    // validate whether the transaction succeeded
    val constructModel = validateTransaction(constructResponseText, localConditions, null, resourceSymbol)

    // set response ETag from created/replaced resource
    handleWrittenResourceEtag(constructModel, prefixes[resourceSymbol]!!)

    // respond with the created resource
    responseContext.createdResource(prefixes[resourceSymbol]!!, constructModel)

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
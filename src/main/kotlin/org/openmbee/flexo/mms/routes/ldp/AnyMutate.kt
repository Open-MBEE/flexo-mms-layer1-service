package org.openmbee.flexo.mms.routes.ldp

import org.openmbee.flexo.mms.ConditionsGroup
import org.openmbee.flexo.mms.KModel
import org.openmbee.flexo.mms.SparqlParameterizer
import org.openmbee.flexo.mms.server.LdpDcLayer1Context
import org.openmbee.flexo.mms.server.LdpMutateResponse

suspend fun <TResponseContext: LdpMutateResponse> LdpDcLayer1Context<TResponseContext>.finalizeMutateTransaction(
    constructString: String,
    localConditions: ConditionsGroup,
    resourceSymbol: String,
    isNewResource: Boolean,
    setup: (SparqlParameterizer.() -> Unit)?=null,
    teardown: (suspend(model: KModel) -> Unit)?=null,
) {
    // execute construct
    val constructResponseText = executeSparqlConstructOrDescribe(constructString, setup)

    // log
    log.info("Finalizing write transaction...\n######## request: ########\n$constructString\n\n######## response: ########\n$constructResponseText")

    // validate whether the transaction succeeded
    val constructModel = validateTransaction(constructResponseText, localConditions, null, resourceSymbol)

    // set response ETag from created/replaced resource
    handleWrittenResourceEtag(constructModel, prefixes[resourceSymbol]!!)

    // new resource
    if(isNewResource) {
        // respond with the created resource
        responseContext.createdResource(prefixes[resourceSymbol]!!, constructModel)
    }
    // existing resource
    else {
        responseContext.mutatedResource(prefixes[resourceSymbol]!!, constructModel)
    }

    // optional teardown
    teardown?.invoke(constructModel)

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
        log.info("Delete transaction response:\n$dropResponseText")
    }
}

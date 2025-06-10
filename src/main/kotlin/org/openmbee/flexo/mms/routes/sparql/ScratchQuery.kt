package org.openmbee.flexo.mms.routes.sparql

import io.ktor.server.routing.*
import org.openmbee.flexo.mms.SCRATCH_QUERY_CONDITIONS
import org.openmbee.flexo.mms.processAndSubmitUserQuery
import org.openmbee.flexo.mms.routes.SCRATCHES_PATH
import org.openmbee.flexo.mms.server.sparqlQuery


/**
 * User submitted SPARQL Query to a scratch space
 */
fun Route.queryScratch() {
    sparqlQuery("$SCRATCHES_PATH/{scratchId}/query/{inspect?}") {
        parsePathParams {
            org()
            repo()
            scratch()
            inspect()
        }

        processAndSubmitUserQuery(requestContext, prefixes["mors"]!!, SCRATCH_QUERY_CONDITIONS)
    }
}

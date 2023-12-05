package org.openmbee.flexo.mms.routes.endpoints

import io.ktor.server.routing.*
import org.openmbee.flexo.mms.DIFF_QUERY_CONDITIONS
import org.openmbee.flexo.mms.assertPreconditions
import org.openmbee.flexo.mms.plugins.sparqlQuery
import org.openmbee.flexo.mms.processAndSubmitUserQuery


/**
 * User submitted SPARQL Query to a specific diff
 */
fun Route.queryDiff() {
    sparqlQuery("/orgs/{orgId}/repos/{repoId}/diff/{diffId}/query/{inspect?}") {
        parsePathParams {
            org()
            repo()
            diff()
            inspect()
        }

        processAndSubmitUserQuery(requestContext, prefixes["mord"]!!, DIFF_QUERY_CONDITIONS.append {
            assertPreconditions(this)
        })
    }
}

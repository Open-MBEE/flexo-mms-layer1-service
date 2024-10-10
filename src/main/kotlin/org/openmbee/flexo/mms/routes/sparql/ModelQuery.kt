package org.openmbee.flexo.mms.routes.sparql

import io.ktor.server.routing.*
import org.openmbee.flexo.mms.BRANCH_QUERY_CONDITIONS
import org.openmbee.flexo.mms.assertPreconditions
import org.openmbee.flexo.mms.server.sparqlQuery
import org.openmbee.flexo.mms.processAndSubmitUserQuery

/**
 * User submitted SPARQL Query to a specific model
 */
fun Route.queryModel() {
    sparqlQuery("/orgs/{orgId}/repos/{repoId}/branches/{branchId}/query/{inspect?}") {
        parsePathParams {
            org()
            repo()
            branch()
            inspect()
        }

        processAndSubmitUserQuery(requestContext, prefixes["morb"]!!, BRANCH_QUERY_CONDITIONS.append {
            assertPreconditions(this)
        })
    }
}

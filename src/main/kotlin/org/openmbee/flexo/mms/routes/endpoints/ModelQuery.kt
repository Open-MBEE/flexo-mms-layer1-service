package org.openmbee.flexo.mms.routes.endpoints

import io.ktor.server.routing.*
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.plugins.sparqlQuery

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

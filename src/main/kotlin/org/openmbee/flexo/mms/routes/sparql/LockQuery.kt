package org.openmbee.flexo.mms.routes.sparql

import io.ktor.server.routing.*
import org.openmbee.flexo.mms.LOCK_QUERY_CONDITIONS
import org.openmbee.flexo.mms.assertPreconditions
import org.openmbee.flexo.mms.server.sparqlQuery
import org.openmbee.flexo.mms.processAndSubmitUserQuery

/**
 * User submitted SPARQL Query to a specific lock
 */
fun Route.queryLock() {
    sparqlQuery("/orgs/{orgId}/repos/{repoId}/locks/{lockId}/query/{inspect?}") {
        parsePathParams {
            org()
            repo()
            lock()
            inspect()
        }

        processAndSubmitUserQuery(requestContext, prefixes["morl"]!!, LOCK_QUERY_CONDITIONS.append {
            assertPreconditions(this)
        })
    }
}

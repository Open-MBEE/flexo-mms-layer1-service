package org.openmbee.flexo.mms.routes.gsp

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.server.GspLayer1Context
import org.openmbee.flexo.mms.server.GspReadResponse
import org.openmbee.flexo.mms.server.SparqlQueryRequest

enum class RefType {
    BRANCH,
    LOCK,
}

suspend fun GspLayer1Context<GspReadResponse>.readModel(refType: RefType) {
    parsePathParams {
        org()
        repo()
        when(refType) {
            RefType.BRANCH -> branch()
            RefType.LOCK -> lock()
        }
    }

    // HEAD method
    if (call.request.httpMethod == HttpMethod.Head) {
        when(refType) {
            RefType.BRANCH -> checkModelQueryConditions(null, prefixes["morb"]!!, BRANCH_QUERY_CONDITIONS.append {
                assertPreconditions(this)
            })
            RefType.LOCK -> checkModelQueryConditions(null, prefixes["morl"]!!, LOCK_QUERY_CONDITIONS.append {
                assertPreconditions(this)
            })
        }
        call.respond(HttpStatusCode.OK)
    }
    // GET
    else {
        val construct = """
            construct { ?s ?p ?o } WHERE { ?s ?p ?o }
        """.trimIndent()
        val requestContext = SparqlQueryRequest(call, construct, setOf(), setOf())
        when(refType) {
            RefType.BRANCH -> processAndSubmitUserQuery(requestContext, prefixes["morb"]!!, BRANCH_QUERY_CONDITIONS.append {
                assertPreconditions(this)
            })
            RefType.LOCK -> processAndSubmitUserQuery(requestContext, prefixes["morl"]!!, LOCK_QUERY_CONDITIONS.append {
                assertPreconditions(this)
            })
        }

    }
}

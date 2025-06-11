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
    SCRATCH,
}

suspend fun GspLayer1Context<GspReadResponse>.readModel(refType: RefType, allData: Boolean?=false) {
    parsePathParams {
        org()
        repo()
        when(refType) {
            RefType.BRANCH -> branch()
            RefType.LOCK -> lock()
            RefType.SCRATCH -> scratch()
        }
    }

    // check conditions
    val targetGraphIri = when(refType) {
        RefType.BRANCH -> checkModelQueryConditions(refIri = prefixes["morb"]!!, conditions = BRANCH_QUERY_CONDITIONS.append {
            assertPreconditions(this)
        })
        RefType.LOCK -> checkModelQueryConditions(refIri = prefixes["morl"]!!, conditions = LOCK_QUERY_CONDITIONS.append {
            assertPreconditions(this)
        })
        RefType.SCRATCH -> checkModelQueryConditions(targetGraphIri = "${prefixes["mor-graph"]}Scratch.$scratchId", conditions = SCRATCH_QUERY_CONDITIONS.append {
            assertPreconditions(this)
        })
    }

    // HEAD method
    if (allData != true) {
        call.respond(HttpStatusCode.OK)
    }
    // GET
    else {
        // select all triples from repo's metadata graph
        val constructResponseText = executeSparqlConstructOrDescribe("""
            construct {
                ?s ?p ?o
            }
            where {
                graph <$targetGraphIri> {
                    ?s ?p ?o
                }
            }
        """.trimIndent()) {
            acceptReplicaLag = true

            prefixes(prefixes)
        }

        // respond to client
        call.respondText(constructResponseText, contentType = RdfContentTypes.Turtle)
    }
}

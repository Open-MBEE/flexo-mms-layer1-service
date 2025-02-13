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

    val authorizedIri = "<${MMS_URNS.SUBJECT.auth}:${transactionId}>"

    val constructString = buildSparqlQuery {
        construct {
            raw("""
                $authorizedIri <${MMS_URNS.PREDICATE.policy}> ?__mms_authMethod . 

                ?s ?p ?o
            """)
        }
        where {
            when(refType) {
                RefType.BRANCH -> auth(Permission.READ_BRANCH.scope.id, BRANCH_QUERY_CONDITIONS)
                RefType.LOCK -> auth(Permission.READ_LOCK.scope.id, LOCK_QUERY_CONDITIONS)
            }

            raw("""
                graph mor-graph:Metadata {
                    ${when(refType) {
                        RefType.BRANCH -> "morb:"
                        RefType.LOCK -> "morl:"
                    }} mms:commit/^mms:commit ?ref .
                    
                    ?ref mms:snapshot ?modelSnapshot .
                    
                    ?modelSnapshot a mms:Model ;
                        mms:graph ?modelGraph .
                }

                graph ?modelGraph {
                    ?s ?p ?o .
                }
            """)
        }
    }

    // finalize construct query and execute
    val constructResponseText = executeSparqlConstructOrDescribe(constructString) {
        acceptReplicaLag = true

        prefixes(prefixes)
    }

    // missing authorized IRI, auth failed
    if(!constructResponseText.contains(authorizedIri)) {
        log("Rejecting unauthorized request with 404\n${constructResponseText}")

        if(call.application.glomarResponse) {
            throw Http404Exception(call.request.path())
        }
        else {
            throw Http403Exception(this, call.request.path())
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

package org.openmbee.mms5.routes

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.openmbee.mms5.*

private val SPARQL_BGP_LOCK = """
    graph mor-graph:Metadata {
        ?_lock a mms:Lock ;
            mms:etag ?__mms_etag ;
            ?lock_p ?lock_o .
        
        optional {
            ?thing mms:lock ?_lock ;
                ?thing_p ?thing_o .
        }
    }
    
    ${permittedActionSparqlBgp(Permission.READ_LOCK, Scope.LOCK)}
"""

private val SPARQL_SELECT_LOCK = """
    select ?__mms_etag {
        $SPARQL_BGP_LOCK
    } order by asc(?__mms_etag)
"""

private val SPARQL_CONSTRUCT_LOCK = """
    construct {
        ?_lock ?lock_p ?lock_o ;
            mms:etag ?__mms_etag .
        
        ?thing ?thing_p ?thing_o .
        
        ?context a mms:Context ;
            mms:permit mms-object:Permission.ReadLock ;
            mms:policy ?policy .
        
        ?__mms_policy ?__mms_policy_p ?__mms_policy_o .
        
        ?lockPolicy ?lockPolicy_p ?lockPolicy_o .
    } where {
        $SPARQL_BGP_LOCK
        
        graph m-graph:AccessControl.Policies {
            ?__mms_policy ?__mms_policy_p ?__mms_policy_o .

            optional {
                ?lockPolicy a mms:Policy ;
                    mms:scope ?_lock ;
                    ?lockPolicy_p ?lockPolicy_o .
            }
        }
        
        bind(bnode() as ?context)
    }
"""

fun Route.readLock() {
    route("/orgs/{orgId}/repos/{repoId}/locks/{lockId?}") {
        head {
            call.mmsL1(Permission.READ_LOCK) {
                // parse path params
                pathParams {
                    org()
                    repo()
                    lock()
                }

                // cache whether this request is asking for all locks
                val allLocks = lockId?.isBlank() ?: true

                // use quicker select query to fetch etags
                val selectResponseText = executeSparqlSelectOrAsk(SPARQL_SELECT_LOCK) {
                    prefixes(prefixes)

                    // get by lockId
                    if(!allLocks) {
                        iri(
                            "_lock" to prefixes["morl"]!!,
                        )
                    }
                }

                // parse the results
                val results = Json.parseToJsonElement(selectResponseText).jsonObject

                // hash all the lock etags
                handleEtagAndPreconditions(results)

                // respond
                call.respondText("")
            }
        }

        get {
            call.mmsL1(Permission.READ_LOCK) {
                // parse path params
                pathParams {
                    org()
                    repo()
                    lock()
                }

                // cache whether this request is asking for all locks
                val allLocks = lockId?.isBlank() ?: true

                // fetch all lock details
                val constructResponseText = executeSparqlConstructOrDescribe(SPARQL_CONSTRUCT_LOCK) {
                    prefixes(prefixes)

                    // get by lockId
                    if(!allLocks) {
                        iri(
                            "_lock" to prefixes["morl"]!!,
                        )
                    }
                }

                // parse the response
                parseConstructResponse(constructResponseText) {
                    // hash all the repo etags
                    if(allLocks) {
                        handleEtagAndPreconditions(model, MMS.Lock)
                    }
                    // just the individual repo
                    else {
                        handleEtagAndPreconditions(model, prefixes["morl"])
                    }
                }

                // respond
                call.respondText(constructResponseText, contentType = RdfContentTypes.Turtle)
            }

        }
    }
}
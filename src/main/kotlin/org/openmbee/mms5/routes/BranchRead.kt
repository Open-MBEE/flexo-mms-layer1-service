package org.openmbee.mms5.routes

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.openmbee.mms5.*

private val SPARQL_BGP_BRANCH = """
    graph mor-graph:Metadata {
        ?_branch a mms:Branch ;
            mms:etag ?__mms_etag ;
            ?branch_p ?branch_o .
        
        optional {
            ?thing mms:branch ?_branch ;
                ?thing_p ?thing_o .
        }
    }
    
    ${permittedActionSparqlBgp(Permission.READ_BRANCH, Scope.BRANCH)}
"""

private val SPARQL_SELECT_BRANCH = """
    select ?__mms_etag {
        $SPARQL_BGP_BRANCH
    } order by asc(?__mms_etag)
"""

private val SPARQL_CONSTRUCT_BRANCH = """
    construct {
        ?_branch ?branch_p ?branch_o ;
            mms:etag ?__mms_etag .
        
        ?thing ?thing_p ?thing_o .
        
        ?context a mms:Context ;
            mms:permit mms-object:Permission.ReadBranch ;
            mms:policy ?policy ;
            .
        
        ?__mms_policy ?__mms_policy_p ?__mms_policy_o .
        
        ?branchPolicy ?branchPolicy_p ?branchPolicy_o .
    } where {
        $SPARQL_BGP_BRANCH
        
        graph m-graph:AccessControl.Policies {
            ?__mms_policy ?__mms_policy_p ?__mms_policy_o .

            optional {
                ?branchPolicy a mms:Policy ;
                    mms:scope ?_branch ;
                    ?branchPolicy_p ?branchPolicy_o .
            }
        }
        
        bind(bnode() as ?context)
    }
"""

fun Route.readBranch() {
    route("/orgs/{orgId}/repos/{repoId}/branches/{branchId?}") {
        head {
            call.mmsL1(Permission.READ_BRANCH) {
                pathParams {
                    org()
                    repo()
                    branch()
                }

                val selectResponseText = executeSparqlSelectOrAsk(SPARQL_SELECT_BRANCH) {
                    prefixes(prefixes)

                    // get by orgId
                    if(false == orgId?.isBlank()) {
                        iri(
                            "_branch" to prefixes["morb"]!!,
                        )
                    }
                }

                val results = Json.parseToJsonElement(selectResponseText).jsonObject

                handleEtagAndPreconditions(results)

                call.respondText("")
            }
        }

        get {
            call.mmsL1(Permission.READ_BRANCH) {
                pathParams {
                    org()
                    repo()
                    branch()
                }

                val constructResponseText = executeSparqlConstructOrDescribe(SPARQL_CONSTRUCT_BRANCH) {
                    prefixes(prefixes)

                    // get by orgId
                    if(false == orgId?.isBlank()) {
                        iri(
                            "_branch" to prefixes["morb"]!!,
                        )
                    }
                }

                val model = KModel(prefixes) {
                    parseTurtle(
                        body = constructResponseText,
                        model = this,
                    )
                }

                handleEtagAndPreconditions(model, prefixes["morb"])

                call.respondText(constructResponseText, contentType = RdfContentTypes.Turtle)
            }

        }
    }
}
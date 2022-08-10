package org.openmbee.mms5.routes

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
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
    select distinct ?__mms_etag {
        $SPARQL_BGP_BRANCH
    } order by asc(?__mms_etag)
"""

private val SPARQL_CONSTRUCT_BRANCH = """
    construct {
        ?_branch ?branch_p ?branch_o ;
            mms:etag ?__mms_etag .
        
        ?thing ?thing_p ?thing_o .
        
        ?_context a mms:Context ;
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
    }
"""

fun Route.readBranch() {
    route("/orgs/{orgId}/repos/{repoId}/branches/{branchId?}") {
        head {
            call.mmsL1(Permission.READ_BRANCH) {
                // parse path params
                pathParams {
                    org()
                    repo()
                    branch()
                }

                // cache whether this request is asking for all branches
                val allBranches = branchId?.isBlank() ?: true

                val sparqlSelect = SPARQL_SELECT_BRANCH.let {
                    if(allBranches) it.replace("""\smorb:(?=\s)""".toRegex(), "") else it
                }

                // use quicker select query to fetch etags
                val selectResponseText = executeSparqlSelectOrAsk(sparqlSelect) {
                    // get by branchId
                    if(!allBranches) {
                        iri(
                            "_branch" to prefixes["morb"]!!,
                        )
                    }

                    prefixes(prefixes)

                    iri(
                        "_context" to "urn:mms:context:$transactionId",
                    )
                }

                // parse the results
                val results = Json.parseToJsonElement(selectResponseText).jsonObject

                // hash all the branch etags
                handleEtagAndPreconditions(results)

                // respond
                call.respondText("")
            }
        }

        get {
            call.mmsL1(Permission.READ_BRANCH) {
                // parse path params
                pathParams {
                    org()
                    repo()
                    branch()
                }

                // cache whether this request is asking for all branches
                val allBranches = branchId?.isBlank() ?: true

                val sparqlConstruct = SPARQL_CONSTRUCT_BRANCH.let {
                    if(allBranches) it.replace("""\smorb:(?=\s)""".toRegex(), "") else it
                }

                // fetch all branch details
                val constructResponseText = executeSparqlConstructOrDescribe(sparqlConstruct) {
                    // get by branchId
                    if(!allBranches) {
                        iri(
                            "_branch" to prefixes["morb"]!!,
                        )
                    }

                    prefixes(prefixes)

                    iri(
                        "_context" to "urn:mms:context:$transactionId",
                    )
                }

                // parse the response
                parseConstructResponse(constructResponseText) {
                    // hash all the repo etags
                    if(allBranches) {
                        handleEtagAndPreconditions(model, MMS.Branch)
                    }
                    // just the individual repo
                    else {
                        handleEtagAndPreconditions(model, prefixes["morb"])
                    }
                }

                // respond
                call.respondText(constructResponseText, contentType = RdfContentTypes.Turtle)
            }

        }
    }
}
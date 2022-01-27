package org.openmbee.routes

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import org.apache.jena.rdf.model.Property
import org.apache.jena.rdf.model.Resource
import org.apache.jena.sparql.modify.request.*
import org.apache.jena.update.Update
import org.apache.jena.update.UpdateFactory
import org.openmbee.*

private const val SPARQL_BGP_STAGING_EXISTS = """
    graph mor-graph:Metadata {
        # select the latest commit from the current named ref
        morb: mms:commit ?baseCommit ;
            .
    
        # and its staging snapshot
        ?staging a mms:Staging ;
            mms:ref morb: ;
            mms:graph ?stagingGraph ;
            .
    
        optional {
            # optionally, it's model snapshot
            ?model a mms:Model ;
                mms:ref morb: ;
                mms:graph ?modelGraph ;
                .
        }
    }
"""

private val DEFAULT_UPDATE_CONDITIONS = REPO_CRUD_CONDITIONS.append {
    permit(Permission.UPDATE_BRANCH, Scope.BRANCH)

    require("stagingExists") {
        handler = { prefixes -> "The destination branch <${prefixes["morb"]}> is corrupt. No staging snapshot found." }

        SPARQL_BGP_STAGING_EXISTS
    }
}



fun Resource.iriAt(property: Property): String? {
    return this.listProperties(property).next()?.`object`?.asResource()?.uri
}

fun assertOperationsAllowed(operations: List<Update>) {
    if(operations.size > 1) {
        if(operations.size == 2) {
            // special case operations can be combined
            if((operations[0] is UpdateDeleteWhere || operations[0] is UpdateDataDelete) && operations[1] is UpdateDataInsert) {
                return
            }
        }

        throw Exception("MMS currently only supports a single SPARQL Update operation at a time, except for DELETE WHERE followed by INSERT DATA")
    }
}


@OptIn(InternalAPI::class)
fun Application.commitBranch() {
    routing {
        post("/orgs/{orgId}/repos/{repoId}/branches/{branchId}/update") {
            call.mmsL1 {
                pathParams {
                    org()
                    repo()
                    branch()
                }

                // parse query
                val sparqlUpdateAst = try {
                    UpdateFactory.create(requestBody)
                } catch(parse: Exception) {
                    throw UpdateSyntaxException(parse)
                }

                var deleteBgpString = ""
                var insertBgpString = ""
                var whereString = ""

                val operations = sparqlUpdateAst.operations

                assertOperationsAllowed(operations)

                for(update in operations) {
                    when(update) {
                        is UpdateDataDelete -> deleteBgpString = asSparqlGroup(update.quads)
                        is UpdateDataInsert -> insertBgpString = asSparqlGroup(update.quads)
                        is UpdateDeleteWhere -> {
                            deleteBgpString = asSparqlGroup(update.quads)
                            whereString = deleteBgpString
                        }
                        is UpdateModify -> {
                            if(update.hasDeleteClause()) {
                                deleteBgpString = asSparqlGroup(update.deleteQuads)
                            }

                            if(update.hasInsertClause()) {
                                insertBgpString = asSparqlGroup(update.insertQuads)
                            }

                            whereString = asSparqlGroup(update.wherePattern.apply {
                                visit(NoQuadsElementVisitor)
                            })
                        }
                        is UpdateAdd -> {

                        }
                        else -> throw UpdateOperationNotAllowedException("SPARQL ${update.javaClass.simpleName} not allowed here")
                    }
                }

                log.info("INSERT: $insertBgpString")
                log.info("DELETE: $deleteBgpString")
                log.info("WHERE: $whereString")


                val interimIri = "${prefixes["mor-lock"]}Iterim.${transactionId}";

                // generate sparql update
                val updateString = buildSparqlUpdate {
                    delete {
                        if(deleteBgpString.isNotEmpty()) {
                            graph("?stagingGraph") {
                                raw(deleteBgpString)
                            }
                        }

                        graph("mor-graph:Metadata") {
                            raw("""
                                # model snapshot no longer points to branch
                                ?model mms:ref morb: .
                        
                                # branch no longer points to previous commit
                                morb: mms:commit ?baseCommit .
                            """)
                        }
                    }
                    insert {
                        txn(
                            "mms-txn:stagingGraph" to "?stagingGraph",
                            "mms-txn:baseModel" to "?model",
                            "mms-txn:baseModelGraph" to "?modelGraph",
                        )

                        if(insertBgpString.isNotEmpty()) {
                            graph("?stagingGraph") {
                                raw(insertBgpString)
                            }
                        }

                        graph("mor-graph:Metadata") {
                            raw("""
                                # new commit
                                morc: a mms:Commit ;
                                    mms:parent ?baseCommit ;
                                    mms:message ?_commitMessage ;
                                    mms:submitted ?_now ;
                                    mms:data morc-data: ;
                                    .
                        
                                # commit data
                                morc-data: a mms:Update ;
                                    mms:body ?_updateBody ;
                                    .
                        
                                # update branch pointer
                                morb: mms:commit morc: .
                        
                                # convert previous snapshot to isolated lock
                                ?_interim a mms:Lock ;
                                    mms:commit ?baseCommit ;
                                    .
                        
                                # model snapshot now points to interim lock
                                ?model mms:ref ?_interim .
                            """)
                        }
                    }
                    where {
                        if(whereString.isNotEmpty()) {
                            graph("?stagingGraph") {
                                raw(whereString)
                            }
                        }

                        raw(
                            *DEFAULT_UPDATE_CONDITIONS.requiredPatterns()
                        )
                    }
                }


                executeSparqlUpdate(updateString) {
                    iri(
                        "_interim" to interimIri,
                    )

                    datatyped(
                        "_updateBody" to (requestBody to MMS_DATATYPE.sparql)
                    )
                }


                val localConditions = DEFAULT_UPDATE_CONDITIONS.append {
                    if(whereString.isNotEmpty()) {
                        inspect("userWhere") {
                            handler = { "User update condition is not satisfiable" }

                            """
                                graph ?stagingGraph {
                                    $whereString
                                }
                            """
                        }
                    }
                }

                // create construct query to confirm transaction and fetch base model details
                val constructResponseText = buildSparqlQuery {
                    construct {
                        txn()

                        raw(
                            """
                            morc: ?commit_p ?commit_o .
                        """
                        )
                    }
                    where {
                        group {
                            raw(
                                """
                                graph mor-graph:Metadata {
                                    morc: ?commit_p ?commit_o .
                                }    
                            """
                            )
                        }
                        raw("""union ${localConditions.unionInspectPatterns()}""")
                    }
                }

                // log
                log.info("Triplestore responded with \n$constructResponseText")

                val constructModel = validateTransaction(constructResponseText, localConditions)

                val transactionNode = constructModel.createResource(prefixes["mt"])

                // fetch staging graph
                val stagingGraph = transactionNode.iriAt(MMS.TXN.stagingGraph)

                // // fetch base model
                // val baseModel = transactionNode.iriAt(MMS.baseModel)
                //
                // // fetch base model graph
                // val baseModelGraph = transactionNode.iriAt(MMS.baseModelGraph)

                // something is wrong
                if(stagingGraph == null) {
                // if(stagingGraph == null || baseModel == null || baseModelGraph == null) {
                    throw Exception("failed to fetch graph/model")
                }

                // forward response to client
                call.respondText(
                    constructResponseText,
                    contentType = RdfContentTypes.Turtle
                )


                // begin copying staging to model
                executeSparqlUpdate("""
                    copy ?_stagingGraph to ?_modelGraph;
                    
                    insert data {
                        graph mor-graph:Metadata {
                            ?_model a mms:Model ;
                                mms:ref morb: ;
                                mms:graph ?_modelGraph ;
                                .
                        }
                    }
                """) {
                    iri(
                        "_stagingGraph" to stagingGraph,
                        "_model" to "${prefixes["mor-snapshot"]}Model.${transactionId}",
                        "_modelGraph" to "${prefixes["mor-graph"]}Model.${transactionId}",
                    )
                }


                // delete transaction
                run {
                    val dropResponseText = executeSparqlUpdate("""
                        delete where {
                            graph m-graph:Transactions {
                                mt: ?p ?o .
                            }
                        }
                    """)

                    // log response
                    log.info(dropResponseText)
                }
            }
        }
    }
}
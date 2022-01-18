package org.openmbee.routes

import io.ktor.application.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import org.apache.jena.rdf.model.Property
import org.apache.jena.rdf.model.Resource
import org.apache.jena.sparql.modify.request.UpdateDataDelete
import org.apache.jena.sparql.modify.request.UpdateDataInsert
import org.apache.jena.sparql.modify.request.UpdateDeleteWhere
import org.apache.jena.sparql.modify.request.UpdateModify
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

private val DEFAULT_UPDATE_CONDITIONS = GLOBAL_CRUD_CONDITIONS.append {
    require("userPermitted") {
        handler = { prefixes -> "User <${prefixes["mu"]}> is not permitted to UpdateBranch." }

        permittedActionSparqlBgp(Permission.UPDATE_BRANCH, Scope.BRANCH)
    }

    require("stagingExists") {
        handler = { prefixes -> "The destination branch <${prefixes["morb"]}> is corrupt. No staging snapshot found." }

        SPARQL_BGP_STAGING_EXISTS
    }
}



fun Resource.iriAt(property: Property): String? {
    return this.listProperties(property).next()?.`object`?.asResource()?.uri
}


@OptIn(InternalAPI::class)
fun Application.updateModel() {
    routing {
        post("/orgs/{orgId}/repos/{repoId}/branches/{branchId}/update") {
            val orgId = call.parameters["orgId"]
            val repoId = call.parameters["repoId"]
            val branchId = call.parameters["branchId"]

            val userId = call.request.headers["mms5-user"]?: ""

            // missing userId
            if(userId.isEmpty()) {
                call.respondText("Missing header: `MMS5-User`")
                return@post
            }

            // create transaction context
            val context = TransactionContext(
                userId=userId,
                orgId=orgId,
                repoId=repoId,
                branchId=branchId,
                request=call.request,
            )

            // ref prefixes
            val prefixes = context.prefixes


            // read entire request body
            val requestBody = call.receiveText()

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

            if(operations.size > 1) {
                throw Exception("MMS currently only supports a single SPARQL Update operation at a time")
            }

            for(operation in operations) {
                operation.visit(object: SelectiveUpdateVisitor() {
                    override fun visit(update: UpdateDataInsert?) {
                        log.info("UpdateDataInsert")
                        if(update != null) {
                            insertBgpString = asSparqlGroup(update.quads);
                        }
                    }

                    override fun visit(update: UpdateDataDelete?) {
                        log.info("UpdateDataDelete")

                        if(update != null) {
                            deleteBgpString = asSparqlGroup(update.quads)
                        }
                    }

                    override fun visit(update: UpdateDeleteWhere?) {
                        log.info("UpdateDeleteWhere")

                        if(update != null) {
                            deleteBgpString = asSparqlGroup(update.quads)
                            whereString = deleteBgpString
                        }
                    }

                    override fun visit(update: UpdateModify?) {
                        log.info("UpdateModify")
                        if(update != null) {
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
                    }
                })


                log.info("INSERT: $insertBgpString")
                log.info("DELETE: $deleteBgpString")
                log.info("WHERE: $whereString")
            }

            val interimIri = "${prefixes["mor-lock"]}Iterim.${context.transactionId}";

            // generate sparql update
            val updateResponse = call.submitSparqlUpdate(context.update {
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
                        "mms:stagingGraph" to "?stagingGraph",
                        "mms:baseModel" to "?model",
                        "mms:baseModelGraph" to "?modelGraph",
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
            }.toString {
                iri(
                    "_interim" to interimIri,
                )

                datatyped(
                    "_updateBody" to (requestBody to MMS_DATATYPE.sparql)
                )
            })

            val localConditions = DEFAULT_UPDATE_CONDITIONS.append {
                if(whereString.isNotEmpty()) {
                    inspect("userWhere") {
                        handler = { "Update condition is not satisfiable" }

                        """
                            graph ?stagingGraph {
                                $whereString
                            }
                        """
                    }
                }
            }

            // create construct query to confirm transaction and fetch base model details
            val constructResponseText = call.submitSparqlConstruct("""
                construct {
                    mt: ?mt_p ?mt_o .
                    
                    morc: ?commit_p ?commit_o .
                    
                    <mms://inspect> <mms://pass> ?inspect .
                }
                where {
                    {
                        graph m-graph:Transactions {
                            mt: ?mt_p ?mt_o .
                        }
            
                        graph mor-graph:Metadata {
                            morc: ?commit_p ?commit_o .
                        }
                    } union ${localConditions.unionInspectPatterns()}
                }
            """) {
                prefixes(context.prefixes)
            }

            // log
            log.info("Triplestore responded with \n$constructResponseText")


            val constructModel = KModel(prefixes).apply {
                // parse model
                parseBody(
                    body = constructResponseText,
                    baseIri = prefixes["mor"]!!,
                    model = this,
                )
            }

            val transactionNode = constructModel.createResource(prefixes["mt"])

            // transaction failed
            if(!transactionNode.listProperties().hasNext()) {
                // use response to diagnose cause
                localConditions.handle(constructModel);

                // the above always throws, so this is unreachable
            }

            // fetch staging graph
            val stagingGraph = transactionNode.iriAt(MMS.stagingGraph)

            // fetch base model
            val baseModel = transactionNode.iriAt(MMS.baseModel)

            // fetch base model graph
            val baseModelGraph = transactionNode.iriAt(MMS.baseModelGraph)

            // something is wrong
            if(stagingGraph == null || baseModel == null || baseModelGraph == null) {
                throw Exception("failed to fetch graph/model")
            }

            // forward response to client
            call.respondText(
                constructResponseText,
                contentType = RdfContentTypes.Turtle
            )


            // begin copying staging to model
            call.submitSparqlUpdate("""
                copy ?_stagingGraph to ?_modelGraph;
                
                insert {
                    graph mor-graph:Metadata {
                        ?_model a mms:Model ;
                            mms:ref morb: ;
                            mms:graph ?_modelGraph ;
                            .
                    }
                }
            """) {
                prefixes(prefixes)

                iri(
                    "_stagingGraph" to stagingGraph,
                    "_model" to "${prefixes["mor-snapshot"]}Model.${context.transactionId}",
                    "_modelGraph" to "${prefixes["mor-graph"]}Model.${context.transactionId}",
                )
            }


            // delete transaction
            run {
                val dropResponseText = call.submitSparqlUpdate("""
                    delete where {
                        graph m-graph:Transactions {
                            mt: ?p ?o .
                        }
                    }
                """) {
                    prefixes(prefixes)
                }

                // log response
                log.info(dropResponseText)
            }
        }
    }
}
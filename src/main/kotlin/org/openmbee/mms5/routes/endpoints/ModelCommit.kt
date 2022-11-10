package org.openmbee.mms5.routes.endpoints

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.apache.jena.rdf.model.Property
import org.apache.jena.rdf.model.Resource
import org.apache.jena.sparql.modify.request.*
import org.apache.jena.update.Update
import org.apache.jena.update.UpdateFactory
import org.openmbee.mms5.*

private val DEFAULT_UPDATE_CONDITIONS = BRANCH_COMMIT_CONDITIONS


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

        throw ServerBugException("MMS currently only supports a single SPARQL Update operation at a time, except for DELETE WHERE followed by INSERT DATA")
    }
}


fun Route.commitModel() {
    post("/orgs/{orgId}/repos/{repoId}/branches/{branchId}/update") {
        call.mmsL1(Permission.UPDATE_BRANCH) {
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

            var patchString = ""
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

            if(whereString.isBlank()) {
                val patches = mutableListOf<String>()

                if(deleteBgpString.isNotBlank()) {
                    patches.add("""
                        delete data {
                            graph ?__mms_model {
                                $deleteBgpString
                            }
                        }
                    """.trimIndent())
                }

                if(insertBgpString.isNotBlank()) {
                    patches.add("""
                        insert data {
                            graph ?__mms_model {
                                $insertBgpString
                            }
                        }
                    """.trimIndent())
                }

                patchString = patches.joinToString(" ; ")
            }
            else {
                if(deleteBgpString.isNotBlank()) {
                    patchString += """
                        delete {
                            graph ?__mms_model {
                                $deleteBgpString
                            }
                        }
                    """.trimIndent()
                }

                if(insertBgpString.isNotBlank()) {
                    patchString += """
                        insert {
                            graph ?__mms_model {
                                $insertBgpString
                            }
                        }
                    """.trimIndent()
                }

                patchString += """
                    where {
                        graph ?__mms_model {
                            $whereString
                        }
                    }
                """.trimIndent()
            }

            log.info("INSERT: $insertBgpString")
            log.info("DELETE: $deleteBgpString")
            log.info("WHERE: $whereString")


            val localConditions = DEFAULT_UPDATE_CONDITIONS.append {
                if(whereString.isNotEmpty()) {
                    inspect("userWhere") {
                        handler = { "User update condition is not satisfiable" to HttpStatusCode.InternalServerError }

                        """
                            graph ?stagingGraph {
                                $whereString
                            }
                        """
                    }
                }

                assertPreconditions(this) {
                    """
                        graph mor-graph:Metadata {
                            morb: mms:etag ?__mms_etag .
                            
                            $it
                        }
                    """
                }
            }



            val commitUpdateString = genCommitUpdate(localConditions,
                delete=if(deleteBgpString.isNotEmpty()) {
                    """
                        graph ?stagingGraph {
                            $deleteBgpString
                        }
                    """.trimIndent()
                } else "",
                insert=if(insertBgpString.isNotEmpty()) {
                    """
                        graph ?stagingGraph {
                            $insertBgpString
                        }
                    """.trimIndent()
                } else "",
                where=(if(whereString.isNotEmpty()) {
                    """
                        graph ?stagingGraph {
                            $whereString
                        }
                    """.trimIndent()
                } else "")
            )

            val interimIri = "${prefixes["mor-lock"]}Interim.${transactionId}"

            // val patchStringCompressed = compressStringLiteral(patchString)

            executeSparqlUpdate(commitUpdateString) {
                prefixes(prefixes)

                iri(
                    "_interim" to interimIri,
                )

                datatyped(
                    "_updateBody" to (requestBody to MMS_DATATYPE.sparql),
                    // "_patchString" to (patchStringCompressed to MMS_DATATYPE.sparqlGz),
                    "_patchString" to (patchString to MMS_DATATYPE.sparql),
                    "_whereString" to (whereString to MMS_DATATYPE.sparql),
                )

                literal(
                    "_txnId" to transactionId,
                )
            }


            // create construct query to confirm transaction and fetch base model details
            val constructString = buildSparqlQuery {
                construct {
                    txn()

                    raw("""
                        morc: ?commit_p ?commit_o .
                    """)
                }
                where {
                    group {
                        txn(null, "morc")

                        raw("""
                            graph mor-graph:Metadata {
                                morc: ?commit_p ?commit_o .
                            }    
                        """)
                    }
                    raw("""union ${localConditions.unionInspectPatterns()}""")
                }
            }

            val constructResponseText = executeSparqlConstructOrDescribe(constructString)

            // log
            log.info("Triplestore responded with \n$constructResponseText")

            val constructModel = validateTransaction(constructResponseText, localConditions, null, "morc")

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
                throw ServerBugException("failed to fetch graph/model")
            }


            // set etag header
            call.response.header(HttpHeaders.ETag, transactionId)

            // provide location of new resource
            call.response.header(HttpHeaders.Location, prefixes["morc"]!!)

            // forward response to client
            call.respondText(
                constructResponseText,
                status = HttpStatusCode.Created,
                contentType = RdfContentTypes.Turtle,
            )

            application.log.info("copy graph <$stagingGraph> to graph ${prefixes["mor-graph"]}Model.${transactionId} ; insert data { graph <${prefixes["mor-graph"]}Metadata> { <urn:a> <urn:b> <urn:c> } }")

            // begin copying staging to model
            executeSparqlUpdate("""
                copy graph <$stagingGraph> to graph ?_modelGraph ;
                
                insert data {
                    graph m-graph:Graphs {
                        ?_modelGraph a mms:SnapshotGraph .
                    }

                    graph mor-graph:Metadata {
                        morb: mms:snapshot ?_model .
                        ?_model a mms:Model ;
                            mms:graph ?_modelGraph ;
                            .
                    }
                }
            """) {
                prefixes(prefixes)

                iri(
                    "_stagingGraph" to stagingGraph,
                    "_model" to "${prefixes["mor-snapshot"]}Model.${transactionId}",
                    "_modelGraph" to "${prefixes["mor-graph"]}Model.${transactionId}",
                )
            }


            // delete transaction
            run {
                val dropTransactionResponseText = executeSparqlUpdate("""
                    delete where {
                        graph m-graph:Transactions {
                            mt: ?p ?o .
                        }
                    }
                """)

                // log response
                log.info(dropTransactionResponseText)

                // delete interim lock
                val dropInterimResponseText = executeSparqlUpdate("""
                    delete where {
                        graph mor-graph:Metadata {
                            ?_interim ?lock_p ?lock_o ;
                                mms:snapshot ?snapshot .
                            
                            ?snapshot mms:graph ?model ;
                                ?snapshot_p ?snapshot_o .
                        }
                        
                        graph m-graph:Graphs {
                            ?model a mms:SnapshotGraph .
                        }
                        
                        graph ?model {
                            ?model_s ?model_p ?model_o .
                        }
                    }
                """) {
                    iri(
                        "_interim" to interimIri,
                    )
                }

                // log response
                log.info(dropInterimResponseText)
            }
        }
    }
}
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


            log("Model commit update:\n\n\tINSERT: $insertBgpString\n\n\tDELETE: $deleteBgpString\n\n\tWHERE: $whereString")

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

            var patchStringDatatype = MMS_DATATYPE.sparql

            // approximate patch string size in bytes by assuming each character is 1 byte
            if(application.gzipLiteralsLargerThanKib?.let { patchString.length > it } == true) {
                compressStringLiteral(patchString)?.let {
                    patchString = it
                    patchStringDatatype = MMS_DATATYPE.sparqlGz
                }
            }

            executeSparqlUpdate(commitUpdateString) {
                prefixes(prefixes)

                iri(
                    "_interim" to interimIri,
                )

                datatyped(
                    "_updateBody" to (requestBody to MMS_DATATYPE.sparql),
                    "_patchString" to (patchString to patchStringDatatype),
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
            log("Triplestore responded with \n$constructResponseText")

            val constructModel = validateTransaction(constructResponseText, localConditions, null, "morc")

            val transactionNode = constructModel.createResource(prefixes["mt"])

            // fetch staging graph
            val stagingGraph = transactionNode.iriAt(MMS.TXN.stagingGraph)
                ?: throw ServerBugException("Branch is missing staging graph")

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

            //
            // ==== Response closed ====
            //

            log("copy graph <$stagingGraph> to graph ${prefixes["mor-graph"]}Model.${transactionId} ; insert data { graph <${prefixes["mor-graph"]}Metadata> { <urn:a> <urn:b> <urn:c> } }")

            // begin copying staging to model
            executeSparqlUpdate("""
                # copy the modified staging graph to become the new model graph
                copy graph <$stagingGraph> to graph ?_modelGraph ;

                insert data {
                    # save new model graph to registry
                    graph m-graph:Graphs {
                        ?_modelGraph a mms:ModelGraph .
                    }

                    graph mor-graph:Metadata {
                        # create model snapshot object in repo's metadata
                        ?_model a mms:Model ;
                            mms:graph ?_modelGraph ;
                            .

                        # assign lock to associate commit with model snapshot
                        mor-lock:Commit.${transactionId} a mms:Lock ;
                            mms:commit morc: ;
                            mms:snapshot ?_model ;
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
                log("Transaction drop response: $dropTransactionResponseText")

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

                // // log response
                // log.info(dropInterimResponseText)
            }
        }
    }
}
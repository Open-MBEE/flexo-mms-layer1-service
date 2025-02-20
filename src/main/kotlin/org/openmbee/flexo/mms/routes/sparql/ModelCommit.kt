package org.openmbee.flexo.mms.routes.sparql

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.jena.rdf.model.Property
import org.apache.jena.rdf.model.Resource
import org.apache.jena.sparql.modify.request.*
import org.apache.jena.update.UpdateFactory
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.server.sparqlUpdate

private val DEFAULT_UPDATE_CONDITIONS = BRANCH_COMMIT_CONDITIONS


fun Resource.iriAt(property: Property): String? {
    return this.listProperties(property).next()?.`object`?.asResource()?.uri
}

fun Route.commitModel() {
    sparqlUpdate("/orgs/{orgId}/repos/{repoId}/branches/{branchId}/update") {
        parsePathParams {
            org()
            repo()
            branch()
        }
        // parse query
        val sparqlUpdateAst = try {
            UpdateFactory.create(requestContext.update)
        } catch(parse: Exception) {
            throw UpdateSyntaxException(parse)
        }
        val localConditions = DEFAULT_UPDATE_CONDITIONS.append {
            assertPreconditions(this) {
                """
                    graph mor-graph:Metadata {
                        morb: mms:etag ?__mms_etag .
                        $it
                    }
                """
            }
        }
        // TODO check this (should be used for model load too)
        val mutex = buildSparqlUpdate {
            insert {
                txn("mms:staging" to "?stagingGraph")
            }
            where {
                raw("""
                    filter not exists {
                        graph m-graph:Transactions { 
                            ?t a mms:Transaction ;
                               mms:branch morb:  .
                        }    
                    }
                """)
                raw("""${localConditions.requiredPatterns().joinToString("\n")}""")
            }
        }
        executeSparqlUpdate(mutex)
        try {
            // TODO check this properly (should be used for model load too)
            val response = executeSparqlSelectOrAsk("""
                select ?stagingGraph from m-graph:Transactions where {mt: mms:staging ?stagingGraph .}
            """.trimIndent())
            val bindings = Json.parseToJsonElement(response).jsonObject["results"]!!.jsonObject["bindings"]!!.jsonArray
            if (bindings.size == 0) {
                throw Http400Exception("fix me ") //mutex failed for whatever reason
            }
            val stagingGraphIri = bindings[0].jsonObject["stagingGraph"]!!.jsonObject["value"]!!.jsonPrimitive.content

            val updates = mutableListOf<String>()
            for (update in sparqlUpdateAst.operations) {
                when (update) {
                    is UpdateDataDelete -> updates.add(
                        """
                        DELETE DATA {
                            graph ?__mms_model {
                                ${asSparqlGroup(update.quads)}
                            }
                        }
                        """.trimIndent()
                    )

                    is UpdateDataInsert -> updates.add(
                        """
                        INSERT DATA {
                            graph ?__mms_model {
                                ${asSparqlGroup(update.quads)}
                            }
                        }
                        """.trimIndent()
                    )

                    is UpdateDeleteWhere -> updates.add(
                        """
                        DELETE WHERE {
                            graph ?__mms_model {
                                ${asSparqlGroup(update.quads)}
                            }
                        }
                        """.trimIndent()
                    )

                    is UpdateModify -> {
                        var modify = "WITH ?__mms_model\n"
                        if (update.hasDeleteClause()) {
                            modify += """
                            DELETE {
                                ${asSparqlGroup(update.deleteQuads)}
                            }
                            """.trimIndent()
                        }
                        if (update.hasInsertClause()) {
                            modify += """
                            INSERT {
                                ${asSparqlGroup(update.insertQuads)}
                            }
                            """.trimIndent()
                        }
                        modify += """
                        WHERE {
                            ${asSparqlGroup(update.wherePattern.apply {
                                visit(NoQuadsElementVisitor)
                            })}
                        }
                        """.trimIndent()
                        updates.add(modify)
                    }

                    else -> throw UpdateOperationNotAllowedException("SPARQL ${update.javaClass.simpleName} not allowed here")
                }
            }
            // this is used for reconstructing graph from previous commit, ?__mms_model should be replaced with graph to apply to
            var patchString = updates.joinToString(";\n")

            // TODO genCommitUpdate can probably be simplified a lot but model load is also using it and it's expecting certain vars, works for now
            // TODO cont. a lot of the conditions checks is now done at the start and transaction insert is duplicating stuff
            updates.add(genCommitUpdate(localConditions))
            val commitUpdateString = updates.joinToString(";\n") //actual update that gets sent

            val interimIri = "${prefixes["mor-lock"]}Interim.${transactionId}"

            var patchStringDatatype = MMS_DATATYPE.sparql

            // approximate patch string size in bytes by assuming each character is 1 byte
            if (application.gzipLiteralsLargerThanKib?.let { patchString.length > it } == true) {
                compressStringLiteral(patchString)?.let {
                    patchString = it
                    patchStringDatatype = MMS_DATATYPE.sparqlGz
                }
            }

            executeSparqlUpdate(commitUpdateString) {
                prefixes(prefixes)

                iri(
                    "_interim" to interimIri,
                    "__mms_model" to stagingGraphIri
                )

                datatyped(
                    "_updateBody" to (requestContext.update to MMS_DATATYPE.sparql),
                    "_patchString" to (patchString to patchStringDatatype),
                    "_whereString" to ("" to MMS_DATATYPE.sparql), //not needed?
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
                        txn(null, true)

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

            //TODO remove transaction here or let finally do it? think it should be done after copy
            //
            // ==== Response closed ====
            //

            log("copy graph <$stagingGraphIri> to graph ${prefixes["mor-graph"]}Model.${transactionId} ;")

            // begin copying staging to model
            executeSparqlUpdate("""
                # copy the modified staging graph to become the new model graph
                copy graph <$stagingGraphIri> to graph ?_modelGraph ;

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
                    "_stagingGraph" to stagingGraphIri,
                    "_model" to "${prefixes["mor-snapshot"]}Model.${transactionId}",
                    "_modelGraph" to "${prefixes["mor-graph"]}Model.${transactionId}",
                )
            }
        } finally {
            val dropTransactionResponseText = executeSparqlUpdate("""
                delete where {
                    graph m-graph:Transactions {
                        mt: ?p ?o .
                    }
                }
            """)
            log("Transaction drop response: $dropTransactionResponseText")
        }
    }
}

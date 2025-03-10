package org.openmbee.flexo.mms.routes.sparql

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
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
        createBranchModifyingTransaction(localConditions)
        try {
            val txnModel = validateBranchModifyingTransaction(localConditions)
            val stagingGraphIri = txnModel.listObjectsOfProperty(
                txnModel.createResource(prefixes["mt"]), MMS.TXN.stagingGraph)
                .next().asResource().uri

            val updates = mutableListOf<String>()

            // merge the client prefixes with internal ones
            val mergedPrefixMap = HashMap(sparqlUpdateAst.prefixMapping.nsPrefixMap)
            mergedPrefixMap.putAll(prefixes.map)

            val mergedPrefixes = withPrefixMap(mergedPrefixMap) {
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
            }

            // this is used for reconstructing graph from previous commit,
            // ?__mms_model will be replaced with graph to apply to during branch/lock graph materialization
            var patchString = mergedPrefixMap.entries.joinToString("\n") {
                "PREFIX ${it.key}: <${it.value}>"
            } + "\n\n" + updates.joinToString(";\n")

            updates.add(genCommitUpdate())
            val commitUpdateString = updates.joinToString(";\n") //actual update that gets sent

            var patchStringDatatype = MMS_DATATYPE.sparql

            // approximate patch string size in bytes by assuming each character is 1 byte
            // TODO this is producing an empty string, need to debug/find alternative to store
            if (application.gzipLiteralsLargerThanKib?.let { patchString.length/1024f > it } == true) {
                compressStringLiteral(patchString)?.let {
                    patchString = it
                    patchStringDatatype = MMS_DATATYPE.sparqlGz
                }
            }
            // still greater than safe maximum
            if (call.application.maximumLiteralSizeKib?.let { patchString.length/1024f > it } == true) {
                log("Compressed patch string still too large")
                // otherwise, just give up
                patchString = "<urn:mms:omitted> <urn:mms:too-large> <urn:mms:to-handle> ."
                patchStringDatatype = MMS_DATATYPE.sparql
            }

            executeSparqlUpdate(commitUpdateString) {
                prefixes(mergedPrefixes)

                iri(
                    "__mms_model" to stagingGraphIri
                )

                datatyped(
                    "_updateBody" to ("" to MMS_DATATYPE.sparql), //find some other way to store if needed
                    "_patchString" to (patchString to patchStringDatatype),
                    "_whereString" to ("" to MMS_DATATYPE.sparql),
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
            val success = constructModel.listObjectsOfProperty(
                constructModel.createResource(prefixes["mt"]), MMS.TXN.success)
                .next()?.asLiteral()?.boolean
            if (success == null) {
                throw HttpException("Sparql Update failed for some reason", HttpStatusCode.BadRequest)
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
                    "_model" to "${prefixes["mor-snapshot"]}Model.${transactionId}",
                    "_modelGraph" to "${prefixes["mor-graph"]}Model.${transactionId}",
                )
            }
        } finally {
            deleteTransaction()
        }
    }
}

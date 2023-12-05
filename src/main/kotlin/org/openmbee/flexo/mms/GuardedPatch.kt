package org.openmbee.flexo.mms

import io.ktor.http.*
import io.ktor.server.response.*
import org.apache.jena.sparql.modify.request.UpdateDataDelete
import org.apache.jena.sparql.modify.request.UpdateDataInsert
import org.apache.jena.sparql.modify.request.UpdateDeleteWhere
import org.apache.jena.sparql.modify.request.UpdateModify
import org.apache.jena.update.UpdateFactory
import org.openmbee.flexo.mms.plugins.SparqlUpdateRequest


suspend fun Layer1Context<*, *>.guardedPatch(updateRequest: SparqlUpdateRequest, objectKey: String, graph: String, preconditions: ConditionsGroup) {
    val baseIri = prefixes[objectKey]!!

    // parse query
    val sparqlUpdateAst = try {
        UpdateFactory.create(updateRequest.update, baseIri)
    } catch(parse: Exception) {
        throw UpdateSyntaxException(parse)
    }

    var deleteBgpString = ""
    var insertBgpString = ""
    var whereString = ""

    val operations = sparqlUpdateAst.operations

    val dataFilter = quadDataFilter(baseIri)
    val patternFilter = quadPatternFilter(baseIri)

    for(update in operations) {
        when(update) {
            is UpdateDataDelete -> deleteBgpString = asSparqlGroup(update.quads, dataFilter)
            is UpdateDataInsert -> insertBgpString = asSparqlGroup(update.quads, dataFilter)
            is UpdateDeleteWhere -> {
                deleteBgpString = asSparqlGroup(update.quads, patternFilter)
                whereString = deleteBgpString
            }
            is UpdateModify -> {
                if(update.hasDeleteClause()) {
                    deleteBgpString = asSparqlGroup(update.deleteQuads, patternFilter)
                }

                if(update.hasInsertClause()) {
                    insertBgpString = asSparqlGroup(update.insertQuads, patternFilter)
                }

                whereString = asSparqlGroup(update.wherePattern.apply {
                    visit(NoQuadsElementVisitor)
                })
            }
            else -> throw UpdateOperationNotAllowedException("SPARQL ${update.javaClass.simpleName} not allowed here")
        }
    }

    log("Guarded patch update:\n\n\tINSERT: $insertBgpString\n\n\tDELETE: $deleteBgpString\n\n\tWHERE: $whereString")

    val conditions = preconditions.append {
        if(whereString.isNotEmpty()) {
            require("userWhere") {
                handler = { "User update condition is not satisfiable" to HttpStatusCode.PreconditionFailed }

                """
                    graph $graph {
                        $whereString
                    }
                """
            }
        }

        // assert any HTTP preconditions supplied by the user
        assertPreconditions(this) {
            """
                graph $graph {
                    $it
                }
            """
        }
    }


    // generate sparql update
    val updateString = buildSparqlUpdate {
        delete {
            graph(graph) {
                raw("""
                    # delete old etag
                    $objectKey: mms:etag ?__mms_etag .
                """)

                if(deleteBgpString.isNotEmpty()) {
                    raw(deleteBgpString)
                }
            }
        }
        insert {
            txn()

            graph(graph) {
                raw("""
                    # set new etag
                    $objectKey: mms:etag ?_txnId .
                """)

                if(insertBgpString.isNotEmpty()) {
                    raw(insertBgpString)
                }
            }
        }
        where {
            raw(*conditions.requiredPatterns())

            graph(graph) {
                raw("""
                    # bind old etag for deletion
                    $objectKey: mms:etag ?__mms_etag .
                """)
            }
        }
    }


    executeSparqlUpdate(updateString) {
        prefixes(prefixes)

        literal(
            "_txnId" to transactionId
        )
    }


    // create construct query to confirm transaction and fetch base model details
    val constructString = buildSparqlQuery {
        construct {
            txn()

            raw("""
                $objectKey: ?w_p ?w_o .
            """)
        }
        where {
            group {
                txn()

                raw("""
                    graph $graph {
                        $objectKey: ?w_p ?w_o .
                    }
                """)
            }
            raw("""union ${conditions.unionInspectPatterns()}""")
        }
    }

    val constructResponseText = executeSparqlConstructOrDescribe(constructString)

    log.info("Post-update construct response:\n$constructResponseText")

    val constructModel = validateTransaction(constructResponseText, conditions)

    // set etag header
    call.response.header(HttpHeaders.ETag, transactionId)

    // forward response to client
    call.respondText(
        constructResponseText,
        contentType = RdfContentTypes.Turtle,
    )

    // delete transaction
    run {
        val dropResponseText = executeSparqlUpdate("""
            delete where {
                graph m-graph:Transactions {
                    mt: ?p ?o .
                }
            }
        """)

        log("Transaction delete response:\n$dropResponseText")
    }
}

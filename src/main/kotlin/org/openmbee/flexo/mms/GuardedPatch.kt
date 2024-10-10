package org.openmbee.flexo.mms

import io.ktor.http.*
import org.apache.jena.sparql.core.Quad
import org.apache.jena.sparql.modify.request.UpdateDataDelete
import org.apache.jena.sparql.modify.request.UpdateDataInsert
import org.apache.jena.sparql.modify.request.UpdateDeleteWhere
import org.apache.jena.sparql.modify.request.UpdateModify
import org.apache.jena.update.UpdateFactory
import org.openmbee.flexo.mms.routes.ldp.finalizeMutateTransaction
import org.openmbee.flexo.mms.server.LdpDcLayer1Context
import org.openmbee.flexo.mms.server.LdpMutateResponse
import org.openmbee.flexo.mms.server.SparqlUpdateRequest


fun quadDataFilter(subjectIri: String): (Quad)->Boolean {
    return {
        it.subject.isURI && it.subject.uri == subjectIri && !it.predicate.uri.contains(FORBIDDEN_PREDICATES_REGEX)
    }
}

fun quadPatternFilter(subjectIri: String): (Quad)->Boolean {
    return {
        if(it.subject.isVariable) {
            throw VariablesNotAllowedInUpdateException("subject")
        }
        else if(!it.subject.isURI || it.subject.uri != subjectIri) {
            throw Http400Exception("All subjects must be exactly <${subjectIri}>. Refusing to evalute ${it.subject}")
        }
        else if(it.predicate.isVariable) {
            throw VariablesNotAllowedInUpdateException("predicate")
        }
        else if(it.predicate.uri.contains(FORBIDDEN_PREDICATES_REGEX)) {
            throw Http400Exception("User not allowed to set property using predicate <${it.predicate.uri}>")
        }

        true
    }
}


suspend fun <TResponseContext: LdpMutateResponse> LdpDcLayer1Context<TResponseContext>.guardedPatch(
    updateRequest: SparqlUpdateRequest,
    objectKey: String,
    graph: String,
    preconditions: ConditionsGroup,
    etagGraph: String=graph
) {
    val baseIri = prefixes[objectKey]!!

    // parse query
    val sparqlUpdateAst = try {
        UpdateFactory.create(updateRequest.update, baseIri)
    } catch(parse: Exception) {
        throw UpdateSyntaxException(parse)
    }

    // normalize operations into delete/insert/where sections
    var deleteBgpString = ""
    var insertBgpString = ""
    var whereString = ""

    // prepare quad filters
    val dataFilter = quadDataFilter(baseIri)
    val patternFilter = quadPatternFilter(baseIri)

    // each operation
    for(update in sparqlUpdateAst.operations) {
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
            // appropriate the 412 HTTP error code to indicate that the user-supplied WHERE block failed as a precondition
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
            graph(etagGraph) {
                raw("""
                    # delete old etag
                    $objectKey: mms:etag ?__mms_etag .
                """)
            }

            graph(graph) {
                if(deleteBgpString.isNotEmpty()) {
                    raw(deleteBgpString)
                }
            }
        }
        insert {
            txn()

            graph(etagGraph) {
                raw("""
                    # set new etag
                    $objectKey: mms:etag ?_txnId .
                """)
            }

            graph(graph) {
                if(insertBgpString.isNotEmpty()) {
                    raw(insertBgpString)
                }
            }
        }
        where {
            raw(*conditions.requiredPatterns())

            graph(etagGraph) {
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

    // finalize transaction
    finalizeMutateTransaction(constructString, conditions, objectKey, false)

//    val constructResponseText = executeSparqlConstructOrDescribe(constructString)
//
//    log.info("Post-update construct response:\n$constructResponseText")
//
//    val constructModel = validateTransaction(constructResponseText, conditions)
//
//    // set etag header
//    call.response.header(HttpHeaders.ETag, transactionId)
//
//    // forward response to client
//    call.respondText(
//        constructResponseText,
//        contentType = RdfContentTypes.Turtle,
//    )
//
//    // delete transaction
//    run {
//        val dropResponseText = executeSparqlUpdate("""
//            delete where {
//                graph m-graph:Transactions {
//                    mt: ?p ?o .
//                }
//            }
//        """)
//
//        log("Transaction delete response:\n$dropResponseText")
//    }
}

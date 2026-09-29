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


fun quadPatternFilter(subjectIri: String): (Quad)->Boolean {
    return {
        if(it.subject.isVariable) {
            throw VariablesNotAllowedInUpdateException("subject")
        }
        else if(!it.subject.isURI || it.subject.uri != subjectIri) {
            throw Http400Exception("All subjects must be exactly <${subjectIri}>. Refusing to evaluate ${it.subject}")
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

    // normalize operations into delete/insert/where sections; multiple operations accumulate and are
    // merged into a single guarded update (all deletes apply before all inserts, and every operation's
    // WHERE pattern must be satisfiable)
    var deleteBgpString = ""
    var insertBgpString = ""
    var whereString = ""

    fun appendGroup(existing: String, addition: String): String {
        return if(existing.isEmpty()) addition else "$existing\n$addition"
    }

    // prepare quad filters
    val patternFilter = quadPatternFilter(baseIri)

    // merge the client prefixes with internal ones
    val mergedPrefixMap = HashMap(sparqlUpdateAst.prefixMapping.nsPrefixMap)
    mergedPrefixMap.putAll(prefixes.map)

    val mergedPrefixes = withPrefixMap(mergedPrefixMap) {
        val sCxt = toSerializationContext()
        for(update in sparqlUpdateAst.operations) {
            when(update) {
                is UpdateDataDelete -> deleteBgpString = appendGroup(deleteBgpString, asSparqlGroup(sCxt, update.quads, patternFilter))
                is UpdateDataInsert -> insertBgpString = appendGroup(insertBgpString, asSparqlGroup(sCxt, update.quads, patternFilter))
                is UpdateDeleteWhere -> {
                    val deleteWhereBgpString = asSparqlGroup(sCxt, update.quads, patternFilter)
                    deleteBgpString = appendGroup(deleteBgpString, deleteWhereBgpString)
                    whereString = appendGroup(whereString, deleteWhereBgpString)
                }
                is UpdateModify -> {
                    if(update.hasDeleteClause()) {
                        deleteBgpString = appendGroup(deleteBgpString, asSparqlGroup(sCxt, update.deleteQuads, patternFilter))
                    }

                    if(update.hasInsertClause()) {
                        insertBgpString = appendGroup(insertBgpString, asSparqlGroup(sCxt, update.insertQuads, patternFilter))
                    }

                    whereString = appendGroup(whereString, asSparqlGroup(sCxt, update.wherePattern.apply {
                        visit(NoQuadsElementVisitor)
                    }))
                }
                else -> throw UpdateOperationNotAllowedException("SPARQL ${update.javaClass.simpleName} not allowed here")
            }
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

        // assert any HTTP preconditions supplied by the user; bind the resource's etag in the same
        // group so the injected filter/values patterns can constrain it
        assertPreconditions(this) {
            """
                graph $etagGraph {
                    $objectKey: mms:etag ?__mms_etag .
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

            // omit the block when empty: some stores (e.g., Virtuoso) reject an empty graph block in an update template
            if(deleteBgpString.isNotBlank()) {
                graph(graph) {
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

            // omit the block when empty: some stores (e.g., Virtuoso) reject an empty graph block in an update template
            if(insertBgpString.isNotBlank()) {
                graph(graph) {
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
        prefixes(mergedPrefixes)

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

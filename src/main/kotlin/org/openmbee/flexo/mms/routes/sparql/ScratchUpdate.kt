package org.openmbee.flexo.mms.routes.sparql;

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.apache.jena.sparql.modify.request.*
import org.apache.jena.update.UpdateFactory
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.routes.SCRATCHES_PATH
import org.openmbee.flexo.mms.server.sparqlUpdate

/**
 * User submitted SPARQL Update to a scratch space
 */
fun Route.updateScratch() {
    sparqlUpdate("$SCRATCHES_PATH/{scratchId}/update") {
        parsePathParams {
            org()
            repo()
            scratch()
        }

        // construct the scratch's named graph IRI
        val scratchGraph = "${prefixes["mor-graph"]}Scratch.$scratchId"
        //val scratchGraph = "mor-graph:Scratch.$scratchId"

        // parse query
        val sparqlUpdateAst = try {
            UpdateFactory.create(requestContext.update)
        } catch (parse: Exception) {
            throw UpdateSyntaxException(parse)
        }

        val localConditions = SCRATCH_UPDATE_CONDITIONS.append {
            assertPreconditions(this) {
                """
                    graph mor-graph:Metadata {
                        mors: mms:etag ?__mms_etag .
                        $it
                    }
                """
            }
        }
        checkModelQueryConditions(targetGraphIri=prefixes["mors"], conditions=localConditions)
        val updates = mutableListOf<String>()
        val prefixMap = HashMap(sparqlUpdateAst.prefixMapping.nsPrefixMap)

        val userPrefixes = withPrefixMap(prefixMap) {
            // each update operation
            for (update in sparqlUpdateAst.operations) {
                // assert that no GRAPH keywords are present in the update (no quad patterns)
                when (update) {
                    is UpdateDataDelete -> updates.add("""
                        DELETE DATA {
                            graph ?__mms_model {
                                ${asSparqlGroup(update.quads)}
                            }
                        }
                    """.trimIndent()
                    )
                    is UpdateDataInsert -> updates.add("""
                        INSERT DATA {
                            graph ?__mms_model {
                                ${asSparqlGroup(update.quads)}
                            }
                        }
                    """.trimIndent()
                    )
                    is UpdateDeleteWhere -> updates.add("""
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
        val updateString = updates.joinToString(";\n")
        // execute the SPARQL UPDATE
        val responseText = executeSparqlUpdate(updateString) {
            prefixes(userPrefixes)

            iri(
                "__mms_model" to scratchGraph
            )
        }

        // forward response to client
        call.respondText(responseText, status = HttpStatusCode.OK)
    }
}

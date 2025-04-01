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
            branch()
        }

        // construct the scratch's named graph IRI
        val scratchGraph = "mor-graph:Scratch.$scratchId"

        // parse query
        val sparqlUpdateAst = try {
            UpdateFactory.create(requestContext.update)
        } catch (parse: Exception) {
            throw UpdateSyntaxException(parse)
        }

        val operations = sparqlUpdateAst.operations

        assertOperationsAllowed(operations)

        // merge the client prefixes with internal ones
        val mergedPrefixMap = HashMap(sparqlUpdateAst.prefixMapping.nsPrefixMap)
        mergedPrefixMap.putAll(prefixes.map)

        // prep SPARQL update string
        var updateString = ""

        withPrefixMap(mergedPrefixMap) {
            // each update operation
            for (update in operations) {
                // prep operation string
                var opString = ""

                // assert that no GRAPH keywords are present in the update (no quad patterns)
                when (update) {
                    is UpdateDataDelete -> opString += """
                        DELETE DATA {
                            graph ?__mms_model {
                                ${asSparqlGroup(update.quads)}
                            }
                        }
                    """.trimIndent()

                    is UpdateDataInsert -> opString += """
                        INSERT DATA {
                            graph ?__mms_model {
                                ${asSparqlGroup(update.quads)}
                            }
                        }
                    """.trimIndent()

                    is UpdateDeleteWhere -> opString += """
                        DELETE WHERE {
                            graph ?__mms_model {
                                ${asSparqlGroup(update.quads)}
                            }
                        }
                    """.trimIndent()

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

                        opString += modify
                    }

                    is UpdateAdd -> {
                        throw UpdateOperationNotAllowedException("SPARQL ADD not allowed here")
                    }

                    else -> throw UpdateOperationNotAllowedException("SPARQL ${update.javaClass.simpleName} not allowed here")
                }

                // put WITH clause before operation
                updateString += "$opString ;\n"
            }
        }

        // execute the SPARQL UPDATE
        val responseText = executeSparqlUpdate(updateString) {
            prefixes(prefixes)

            iri(
                "__mms_model" to scratchGraph
            )
        }

        // forward response to client
        call.respondText(responseText, status = HttpStatusCode.OK)
    }
}

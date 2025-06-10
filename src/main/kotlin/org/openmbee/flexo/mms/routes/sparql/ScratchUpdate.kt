package org.openmbee.flexo.mms.routes.sparql;

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
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
        val prefixMap = HashMap(sparqlUpdateAst.prefixMapping.nsPrefixMap)
        val updates = prepareUserUpdate(sparqlUpdateAst, prefixMap)
        val userPrefixes = PrefixMapBuilder()
        userPrefixes.map = prefixMap
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

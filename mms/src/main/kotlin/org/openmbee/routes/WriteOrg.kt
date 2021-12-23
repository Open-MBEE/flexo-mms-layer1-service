package org.openmbee.routes

import io.ktor.application.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.*
import org.openmbee.plugins.client


private const val SPARQL_INSERT_ORG = """
    insert {
        graph m-graph:Cluster {
            @_orgBody
        }
    } where {
        filter not exists {
            graph m-graph:Cluster {
                mo: a mms:Org .
            }
        }
    }
"""


@OptIn(InternalAPI::class)
fun Application.writeOrg() {
    routing {
        put("/orgs/{orgId}") {
            val orgId = call.parameters["orgId"]!!
            //
            // val reqParams = call.request.queryParameters
            // val orgTitle = reqParams.getOrFail("title").trim()

            // initialize prefixes
            var prefixes = prefixesFor(orgId=orgId)

            // create a working model to prepare the Update
            val workingModel = KModel(prefixes)

            // create org node
            val orgNode = workingModel.createResource(prefixes["mo"])

            // read put contents
            parseBody(
                body=call.receiveText(),
                prefixes=prefixes,
                baseIri=orgNode.uri,
                model=workingModel,
            )


            // set system-controlled properties and remove conflicting triples from user input
            orgNode.run {
                removeAll(RDF.type)
                removeAll(MMS.id)

                // normalize dct:title
                run {
                    // remove triples that don't point to literals
                    listProperties(DCTerms.title)
                        .forEach {
                            if(!it.`object`.isLiteral) {
                                workingModel.remove(it)
                            }
                        }
                }

                addProperty(RDF.type, MMS.Org)
                addProperty(MMS.id, orgId)
            }

            // serialize org node
            val orgTriples = KModel(prefixes) {
                removeNsPrefix("m-org")
                add(orgNode.listProperties())
            }.stringify(emitPrefixes=false)

            // generate sparql update
            val sparqlUpdate = parameterizedSparql(
                SPARQL_INSERT_ORG.replace(
                    "^\\s*@_orgBody".toRegex(RegexOption.MULTILINE),
                    orgTriples.prependIndent("\t\t")
                )
            ) {
                prefixes(prefixes)
            }

            // log
            log.info(sparqlUpdate)


            // execute
            val updateResponse = client.submitSparqlUpdate(sparqlUpdate)

            call.respondText(updateResponse.readText(), status=updateResponse.status, contentType=updateResponse.contentType())
        }
    }
}
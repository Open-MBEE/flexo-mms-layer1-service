package org.openmbee.mms5.routes.endpoints

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
import org.apache.jena.graph.NodeFactory
import org.apache.jena.graph.Triple
import org.apache.jena.sparql.syntax.ElementGroup
import org.apache.jena.sparql.syntax.ElementNamedGraph
import org.apache.jena.sparql.syntax.ElementTriplesBlock
import org.openmbee.mms5.*
import java.util.*


fun Route.queryRepo() {
    post("/orgs/{orgId}/repos/{repoId}/query/{inspect?}") {
        call.mmsL1(Permission.READ_REPO) {
            pathParams {
                org()
                repo()
                inspect()
            }

            // auto-inject default prefixes
            val inputQueryString = "$prefixes\n$requestBody"

            // sanitize user query
            val (rewriter, workingQuery) = sanitizeUserQuery(inputQueryString, prefixes["mor"])

            // generate a unique substitute variable
            val substituteVar = NodeFactory.createVariable("__mms_${UUID.randomUUID().toString().replace('-', '_')}")

            workingQuery.apply {
                // create new group
                val group = ElementGroup()

                // start by injecting a substitution pattern
                run {
                    val bgp = ElementTriplesBlock()
                    bgp.addTriple(Triple.create(substituteVar, substituteVar, substituteVar))
                    group.addElement(bgp)
                }

                // create metadata graph URI node
                val metadataGraphNode = NodeFactory.createURI("${prefixes["mor-graph"]}Metadata")

                // add all prepend root elements
                rewriter.prepend.forEach { group.addElement(it) }

                // wrap original element in metadata graph
                group.addElement(ElementNamedGraph(metadataGraphNode, queryPattern))

                // add all append root elements
                rewriter.append.forEach { group.addElement(it) }

                // set new pattern
                queryPattern = group

                // unset query result star
                if(isQueryResultStar) {
                    isQueryResultStar = false
                }

                // resetResultVars()
                log.info("vars: $resultVars")
            }

            // serialize the query and replace the substitution pattern with conditions
            val outputQueryString = workingQuery.serialize().replace(
                """[?$]${substituteVar.name}\s+[?$]${substituteVar.name}\s+[?$]${substituteVar.name}\s*\.?""".toRegex(),
                REPO_QUERY_CONDITIONS.requiredPatterns().joinToString("\n"))


            if(inspectOnly) {
                call.respondText(outputQueryString)
                return@mmsL1
            }
            else {
                log.info(outputQueryString)
            }

            if(workingQuery.isSelectType || workingQuery.isAskType) {
                val queryResponseText = executeSparqlSelectOrAsk(outputQueryString) {}

                call.respondText(queryResponseText, contentType=RdfContentTypes.SparqlResultsJson)
            }
            else if(workingQuery.isConstructType || workingQuery.isDescribeType) {
                val queryResponseText = executeSparqlConstructOrDescribe(outputQueryString) {}

                call.respondText(queryResponseText, contentType=RdfContentTypes.Turtle)
            }
            else {
                throw Exception("Query operation not supported")
            }
        }

    }
}

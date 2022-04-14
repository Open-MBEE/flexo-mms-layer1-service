package org.openmbee.mms5.routes.endpoints

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
import org.apache.jena.graph.NodeFactory
import org.apache.jena.sparql.syntax.ElementGroup
import org.apache.jena.sparql.syntax.ElementNamedGraph
import org.openmbee.mms5.Permission
import org.openmbee.mms5.RdfContentTypes
import org.openmbee.mms5.mmsL1
import org.openmbee.mms5.sanitizeUserQuery


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

            workingQuery.apply {
                // create new group
                val group = ElementGroup()

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


            val outputQueryString = workingQuery.serialize()

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

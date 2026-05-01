package org.openmbee.flexo.mms.routes.sparql

import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.apache.jena.rdf.model.Property
import org.apache.jena.rdf.model.Resource
import org.apache.jena.update.UpdateFactory
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.server.sparqlUpdate

private val DEFAULT_UPDATE_CONDITIONS = BRANCH_COMMIT_CONDITIONS

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
        // Fail fast: reject named graphs in user update before creating a transaction
        rejectGraphInUserUpdate(sparqlUpdateAst)

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
            val localPrefixes = prefixes
            val mtResource = txnModel.createResource(localPrefixes["mt"])
            val stagingGraphIri = txnModel.listObjectsOfProperty(
                mtResource, MMS.TXN.stagingGraph)
                .next().asResource().uri
            val modelGraphIri = txnModel.listObjectsOfProperty(
                mtResource, MMS.TXN.baseModelGraph)
                .next().asResource().uri
            val baseCommitIri = txnModel.listObjectsOfProperty(
                mtResource, MMS.TXN.baseCommit)
                .next().asResource().uri
            val prefixMap = HashMap(sparqlUpdateAst.prefixMapping.nsPrefixMap)
            val (updateString, userPrefixes) = prepareUserUpdate(sparqlUpdateAst, prefixMap)
            //run update, will throw error if triplestore response is not 2xx
            executeSparqlUpdate(updateString) {
                prefixes(userPrefixes)
                iri("__mms_model" to stagingGraphIri)
            }
            val commitUpdateString = genCommitUpdate()
            val constructResponseText = diffAndFinalizeCommit(
                stagingGraphIri,
                modelGraphIri,
                baseCommitIri,
                localConditions,
                commitUpdateString)
            if (constructResponseText.isBlank()) {
                //there's no diff, already responded
                return@sparqlUpdate
            }
            // set etag header
            call.response.header(HttpHeaders.ETag, transactionId)
            // provide location of new resource
            call.response.header(HttpHeaders.Location, localPrefixes["morc"]!!)

            // forward response to client
            call.respondText(
                constructResponseText,
                status = HttpStatusCode.Created,
                contentType = RdfContentTypes.Turtle,
            )
            /*
            call.application.launch(Dispatchers.IO) {
                try {
                    cleanupPreviousCommitLock(baseCommitIri)
                } catch (e: Exception) {
                    log.warn("Failed to cleanup previous commit lock for $baseCommitIri: ${e.message}")
                }
            }
            */
        } finally {
            deleteTransaction()
        }
    }
}

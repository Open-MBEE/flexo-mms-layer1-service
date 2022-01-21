package org.openmbee.routes

import io.ktor.application.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import org.apache.jena.rdf.model.impl.ResourceImpl
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.*
import javax.annotation.Resource


private val DEFAULT_CONDITIONS = COMMIT_CRUD_CONDITIONS.append {
    permit(Permission.CREATE_LOCK, Scope.REPO)

    require("lockNotExists") {
        handler = { prefixes -> "The provided lock <${prefixes["morcl"]}> already exists." }

        """
            # lock must not yet exist
            graph m-graph:Cluster {
                filter not exists {
                    morcl: a mms:Lock .
                }
            }
        """
    }
}


@OptIn(InternalAPI::class)
fun Application.createLock() {
    routing {
        put("/orgs/{orgId}/repos/{repoId}/commits/{commitId}/locks/{lockId}") {
            val orgId = call.parameters["orgId"]
            val repoId = call.parameters["repoId"]
            val commitId = call.parameters["commitId"]
            val lockId = call.parameters["lockId"]!!
            val userId = call.mmsUserId

            // missing userId
            if(userId.isEmpty()) {
                call.respondText("Missing header: `MMS5-User`")
                return@put
            }

            // assert id is valid
            call.assertLegalId(lockId)

            // read request body
            val requestBody = call.receiveText()

            // create transaction context
            val context = TransactionContext(
                userId=userId,
                orgId=orgId,
                repoId=repoId,
                commitId=commitId,
                lockId=lockId,
                request=call.request,
                requestBody=requestBody,
            )

            // initialize prefixes
            val prefixes = context.prefixes

            // create a working model to prepare the Update
            val workingModel = KModel(prefixes)

            // create lock node
            val lockNode = workingModel.createResource(prefixes["morcl"])

            // read put contents
            parseBody(
                body=requestBody,
                prefixes=prefixes,
                baseIri=lockNode.uri,
                model=workingModel,
            )

            // set system-controlled properties and remove conflicting triples from user input
            lockNode.run {
                removeAll(RDF.type)
                removeAll(MMS.id)
                removeAll(MMS.commit)
                removeAll(MMS.createdBy)

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

                addProperty(RDF.type, MMS.Lock)
                addProperty(MMS.id, lockId)
                addProperty(MMS.commit, ResourceImpl(prefixes["morc"]))
                addProperty(MMS.createdBy, ResourceImpl(prefixes["mu"]))
            }

            // serialize lock node
            val lockTriples = KModel(prefixes) {
                add(lockNode.listProperties())
            }.stringify(emitPrefixes=false)

            // generate sparql update
            val sparqlUpdate = context.update {
                insert {
                    txn()

                    graph("mor-graph:Metadata") {
                        raw(lockTriples)
                    }

                    // auto-policy
                    autoPolicySparqlBgp(
                        builder=this,
                        prefixes=prefixes,
                        scope=Scope.LOCK,
                        roles=listOf(Role.ADMIN_LOCK),
                    )
                }
                where {
                    raw(
                        *DEFAULT_CONDITIONS.requiredPatterns()
                    )
                }
            }.toString()


            // log
            log.info(sparqlUpdate)

            // submit update
            val updateResponse = call.submitSparqlUpdate(sparqlUpdate)

            val localConditions = DEFAULT_CONDITIONS

            // create construct query to confirm transaction and fetch project details
            val constructResponseText = call.submitSparqlConstructOrDescribe("""
                construct  {
                    mt: ?mt_p ?mt_o .

                    morcl: ?morcl_p ?morcl_o .
                    
                    ?policy ?policy_p ?policy_o .
                    
                    <mms://inspect> <mms://pass> ?inspect .
                } where {
                    {
                        graph m-graph:Transactions {
                            mt: ?mt_p ?mt_o .
                        }

                        graph mor-graph:Metadata {
                            morcl: ?morcl_p ?morcl_o .
                        }
                        
                        graph m-graph:AccessControl.Policies {
                            optional {
                                ?policy mms:scope morcl: ;
                                    ?policy_p ?policy_o .
                            }
                        }
                    } union ${localConditions.unionInspectPatterns()}
                }
            """) {
                prefixes(context.prefixes)
            }

            // log
            log.info("Triplestore responded with:\n$constructResponseText")

            // parse model
            val constructModel = KModel(prefixes).apply {
                parseBody(
                    body = constructResponseText,
                    baseIri = lockNode.uri,
                    model = this,
                )
            }

            val transactionNode = constructModel.createResource(prefixes["mt"])

            // transaction failed
            if(!transactionNode.listProperties().hasNext()) {
                // use response to diagnose cause
                localConditions.handle(constructModel);

                // the above always throws, so this is unreachable
            }

            // respond
            call.respondText(constructResponseText, RdfContentTypes.Turtle)

            // delete transaction
            run {
                // submit update
                val dropResponseText = call.submitSparqlUpdate("""
                    delete where {
                        graph m-graph:Transactions {
                            mt: ?p ?o .
                        }
                    }
                """) {
                    prefixes(prefixes)
                }

                // log response
                log.info(dropResponseText)
            }
        }
    }
}
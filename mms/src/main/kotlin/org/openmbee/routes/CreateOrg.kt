package org.openmbee.routes

import io.ktor.application.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.*


private val DEFAULT_CONDITIONS = GLOBAL_CRUD_CONDITIONS.append {
    require("userPermitted") {
        handler = { prefixes -> "User <${prefixes["mu"]}> is not permitted to CreateOrg." }

        permittedActionSparqlBgp(Permission.CREATE_ORG, Scope.CLUSTER)
    }

    require("orgNotExists") {
        handler = { prefixes -> "The provided org <${prefixes["mo"]}> already exists." }

        """
            # org must not yet exist
            graph m-graph:Cluster {
                filter not exists {
                    mo: a mms:Org .
                }
            }
        """
    }
}


@OptIn(InternalAPI::class)
fun Application.createOrg() {
    routing {
        put("/orgs/{orgId}") {
            val orgId = call.parameters["orgId"]!!

            val userId = call.request.headers["mms5-user"]?: ""

            // missing userId
            if(userId.isEmpty()) {
                call.respondText("Missing header: `MMS5-User`")
                return@put
            }

            // read request body
            val requestBody = call.receiveText()

            // create transaction context
            val context = TransactionContext(
                userId=userId,
                orgId=orgId,
                request=call.request,
                requestBody=requestBody,
            )

            // initialize prefixes
            var prefixes = context.prefixes

            // create a working model to prepare the Update
            val workingModel = KModel(prefixes)

            // create org node
            val orgNode = workingModel.createResource(prefixes["mo"])

            // read put contents
            parseBody(
                body=requestBody,
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
            val sparqlUpdate = context.update {
                insert {
                    txn()

                    graph("m-graph:Cluster") {
                        raw(orgTriples)
                    }

                    // auto-policy
                    autoPolicySparqlBgp(
                        builder=this,
                        prefixes=prefixes,
                        scope=Scope.ORG,
                        roles=listOf(Role.ADMIN_ORG),
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
            val constructResponseText = call.submitSparqlConstruct("""
                construct  {
                    mt: ?mt_p ?mt_o .

                    mo: ?mo_p ?mo_o .                    
                    
                    ?policy ?policy_p ?policy_o .
                    
                    <mms://inspect> <mms://pass> ?inspect .
                } where {
                    {
                        graph m-graph:Transactions {
                            mt: ?mt_p ?mt_o .
                        }

                        graph m-graph:Cluster {
                            mo: ?mo_p ?mo_o .
                        }
                        
                        graph m-graph:AccessControl.Policies {
                            optional {
                                ?policy mms:scope mo: ;
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
                    baseIri = orgNode.uri,
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
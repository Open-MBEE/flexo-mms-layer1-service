package org.openmbee.routes

import io.ktor.application.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.*


private val SPARQL_CONSTRUCT_TRANSACTION: (conditions: ConditionsGroup)->String = { """
    construct  {
        mor: ?mor_p ?mor_o .
        
        ?thing ?thing_p ?thing_o .
        
        ?policy ?policy_p ?policy_o .
        
        ?m_s ?m_p ?m_o .
        
        mt: ?mt_p ?mt_o .
    } where {
        {
            graph m-graph:Transactions {
                mt: ?mt_p ?mt_o .
            }
    
            graph m-graph:Cluster {
                mor: a mms:Repo ;
                    ?mor_p ?mor_o ;
                    .
    
                optional {
                    ?thing mms:repo mor: ; 
                        ?thing_p ?thing_o .
                }
            }
    
            graph m-graph:AccessControl.Policies {
                ?policy mms:scope mor: ;
                    ?policy_p ?policy_o .
            }
        
            graph mor-graph:Metadata {
                ?m_s ?m_p ?m_o .
            }
        } union ${it.unionInspectPatterns()}
    }
"""}


private val DEFAULT_CONDITIONS = GLOBAL_CRUD_CONDITIONS.append {
    require("userPermitted") {
        handler = { prefixes -> "User <${prefixes["mu"]}> is not permitted to CreateOrg." }

        permittedActionSparqlBgp(Permission.CREATE_REPO, Scope.REPO)
    }

    require("orgExists") {
        handler = { prefixes -> "Org <${prefixes["mo"]}> does not exist." }

        """
            # org must exist
            graph m-graph:Cluster {
                mo: a mms:Org .
            }
        """
    }

    require("repoNotExists") {
        handler = { prefixes -> "The provided repo <${prefixes["mor"]}> already exists." }

        """
            # repo must not yet exist
            graph m-graph:Cluster {
                filter not exists {
                    mor: a mms:Repo .
                }
            }
        """
    }

    require("repoMetadataGraphEmpty") {
        handler = { prefixes -> "The Metadata graph <${prefixes["mor-graph"]}Metadata> is not empty." }

        """
            # repo metadata graph must be empty
            graph mor-graph:Metadata {
                filter not exists {
                    ?e_s ?e_p ?e_o .
                }
            }
        """
    }
}

fun String.normalizeIndentation(spaces: Int=0): String {
    return this.trimIndent().prependIndent(" ".repeat(spaces)).replace("^\\s+".toRegex(), "")
}

@OptIn(InternalAPI::class)
fun Application.createRepo() {
    routing {
        put("/orgs/{orgId}/repos/{repoId}") {
            val orgId = call.parameters["orgId"]!!
            val repoId = call.parameters["repoId"]!!
            val userId = call.mmsUserId

            // missing userId
            if(userId.isEmpty()) {
                call.respondText("Missing header: `MMS5-User`")
                return@put
            }


            val branchId = "main"

            // create transaction context
            val context = TransactionContext(
                userId=userId,
                orgId=orgId,
                repoId=repoId,
                branchId=branchId,
                request=call.request,
            )

            // initialize prefixes
            var prefixes = context.prefixes

            // create a working model to prepare the Update
            val workingModel = KModel(prefixes)

            // create org node
            var orgNode = workingModel.createResource(prefixes["mo"])

            // create repo node
            val repoNode = workingModel.createResource(prefixes["mor"])

            // read entire request body
            val requestBody = call.receiveText()

            // read put contents
            parseBody(
                body=requestBody,
                prefixes=prefixes,
                baseIri=repoNode.uri,
                model=workingModel,
            )

            // set system-controlled properties and remove conflicting triples from user input
            repoNode.run {
                removeAll(RDF.type)
                removeAll(MMS.id)
                removeAll(MMS.org)

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

                // add back the approved properties
                addProperty(RDF.type, MMS.Repo)
                addProperty(MMS.id, repoId)
                addProperty(MMS.org, orgNode)
            }

            // serialize repo node
            val repoTriples = KModel(prefixes) {
                add(repoNode.listProperties())
            }.stringify(emitPrefixes=false)

            val localConditions = DEFAULT_CONDITIONS

            // generate sparql update
            val updateResponse = call.submitSparqlUpdate(context.update {
                insert {
                    txn()

                    graph("m-graph:Cluster") {
                        raw(repoTriples)
                    }

                    graph("mor-graph:Metadata") {
                        raw("""
                            morc: a mms:Commit ;
                                mms:parent rdf:nil ;
                                mms:submitted ?_now ;
                                mms:message ?_commitMessage ;
                                mms:data morc-data: ;
                                .
                    
                            morc-data: a mms:Load ;
                                .

                            morb: a mms:Branch ;
                                mms:id ?_branchId ;
                                mms:commit morc: ;
                                .
                            
                            ?_model a mms:Model ;
                                mms:ref morb: ;
                                mms:graph ?_modelGraph ;
                                .
                                
                            ?_staging a mms:Staging ;
                                mms:ref morb: ;
                                mms:graph ?_stagingGraph ;
                                .
                        """)
                    }

                    autoPolicySparqlBgp(
                        builder=this,
                        prefixes=prefixes,
                        scope=Scope.REPO,
                        roles=listOf(Role.ADMIN_REPO),
                    )
                }
                where {
                    raw(
                        *localConditions.requiredPatterns()
                    )
                }
            }.toString {
                iri(
                    "_model" to "${prefixes["mor-snapshot"]}Model.${context.transactionId}",
                    "_modelGraph" to "${prefixes["mor-graph"]}Model.${context.transactionId}",
                    "_staging" to "${prefixes["mor-snapshot"]}Staging.${context.transactionId}",
                    "_stagingGraph" to "${prefixes["mor-graph"]}Staging.${context.transactionId}",
                )
            })


            // create construct query to confirm transaction and fetch repo details
            val constructResponseText = call.submitSparqlConstructOrDescribe(SPARQL_CONSTRUCT_TRANSACTION(localConditions)) {
                prefixes(context.prefixes)
            }

            val constructModel = KModel(prefixes)

            // parse model
            parseBody(
                body=constructResponseText,
                baseIri=repoNode.uri,
                model=constructModel,
            )

            val transactionNode = constructModel.createResource(prefixes["mt"])

            // transaction failed
            if(!transactionNode.listProperties().hasNext()) {
                localConditions.handle(constructModel)
            }

            // respond
            call.respondText(constructResponseText, contentType=RdfContentTypes.Turtle)

            // delete transaction graph
            run {
                // prepare SPARQL DROP
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

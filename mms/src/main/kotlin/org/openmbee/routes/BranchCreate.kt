package org.openmbee.routes

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import org.apache.jena.rdf.model.impl.ResourceImpl
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.*


private val DEFAULT_CONDITIONS = COMMIT_CRUD_CONDITIONS.append {
    permit(Permission.CREATE_BRANCH, Scope.REPO)

    require("branchNotExists") {
        handler = { prefixes -> "The provided branch <${prefixes["morb"]}> already exists." }

        """
            # branch must not yet exist
            graph m-graph:Cluster {
                filter not exists {
                    morb: a mms:Branch .
                }
            }
        """
    }
}


@OptIn(InternalAPI::class)
fun Application.createBranch() {
    routing {
        put("/orgs/{orgId}/repos/{repoId}/branches/{branchId}") {
            val context = call.normalize {
                user()
                org()
                repo()
                branch(legal=true)
            }

            // ref prefixes
            val prefixes = context.prefixes

            // create a working model to prepare the Update
            val workingModel = KModel(prefixes)

            // create branch node
            val branchNode = workingModel.createResource(prefixes["morb"])

            // read put contents
            parseBody(
                body=context.requestBody,
                prefixes=prefixes,
                baseIri=branchNode.uri,
                model=workingModel,
            )

            var refSource: String? = null
            var commitSource: String? = null

            // set system-controlled properties and remove conflicting triples from user input
            branchNode.run {
                removeAll(RDF.type)
                removeAll(MMS.id)
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

                    // assert ref/commit triples
                    val refs = listProperties(MMS.ref).toList()
                    val commits = listProperties(MMS.commit).toList()
                    val sourceCount = refs.size + commits.size
                    if(1 == sourceCount) {
                        if(1 == refs.size) {
                            refSource = if(!refs[0].`object`.isURIResource) {
                                throw Exception("Ref source must be an IRI")
                            } else {
                                refs[0].`object`.asResource().uri
                            }
                        }
                        else {
                            commitSource = if(!commits[0].`object`.isURIResource) {
                                throw Exception("Commit source must be an IRI")
                            } else {
                                commits[0].`object`.asResource().uri
                            }
                        }
                    }
                    else if(0 == sourceCount) {
                        throw Exception("Must specify a ref or commit source using mms:ref or mms:commit predicate, respectively.")
                    }
                    else if(sourceCount > 1) {
                        throw Exception("Too many sources specified.")
                    }

                    removeAll(MMS.ref)
                    removeAll(MMS.commit)
                }

                addProperty(RDF.type, MMS.Branch)
                addProperty(MMS.id, context.branchId)
                addProperty(MMS.createdBy, ResourceImpl(prefixes["mu"]))
            }

            val localConditions = DEFAULT_CONDITIONS.append {
                require("validSource") {
                    handler = { prefixes -> "Invalid ${if(refSource != null) "ref" else "commit"} source" }

                    """
                        graph mor-graph:Metadata {
                            ${if(refSource != null) """
                                ?_refSource a/rdfs:subClassOf* mms:Ref ;
                                    mms:commit ?commitSource ;
                                    .
                            """ else ""} 
                           
                           ?commitSource a mms:Commit .
                        }
                    """
                }
            }

            // serialize lock node
            val branchTriples = KModel(prefixes) {
                add(branchNode.listProperties())
            }.stringify(emitPrefixes=false)

            // generate sparql update
            val sparqlUpdate = context.update {
                insert {
                    txn(
                        "mms-txn:sourceGraph" to "sourceGraph",
                    )

                    graph("mor-graph:Metadata") {
                        raw("""
                            $branchTriples
                            
                            morb: mms:commit ?commitSource .
                        """)
                    }

                    // auto-policy
                    autoPolicySparqlBgp(
                        builder=this,
                        prefixes=prefixes,
                        scope=Scope.BRANCH,
                        roles=listOf(Role.ADMIN_BRANCH),
                    )
                }
                where {
                    raw(
                        *localConditions.requiredPatterns()
                    )

                    graph("mor-graph:Metadata") {
                        raw("""
                            optional {
                                ?snapshot a/rdfs:subClassOf* mms:Snapshot ;
                                    mms:ref/mms:commit ?commitSource ;
                                    mms:graph ?sourceGraph ;
                                    .
                            }
                        """)
                    }
                }
            }.toString() {
                iri(
                    if(refSource != null) "refSource" to refSource!!
                    else "commitSource" to commitSource!!,
                )
            }


            // log
            log.info(sparqlUpdate)

            // submit update
            val updateResponse = call.submitSparqlUpdate(sparqlUpdate)

            // create construct query to confirm transaction and fetch project details
            val constructResponseText = call.submitSparqlConstructOrDescribe("""
                construct  {
                    mt: ?mt_p ?mt_o .

                    morb: ?morb_p ?morb_o .
                    
                    ?policy ?policy_p ?policy_o .
                    
                    <mms://inspect> <mms://pass> ?inspect .
                } where {
                    {
                        graph m-graph:Transactions {
                            mt: ?mt_p ?mt_o .
                        }

                        graph mor-graph:Metadata {
                            morb: ?morb_p ?morb_o .
                        }
                        
                        graph m-graph:AccessControl.Policies {
                            optional {
                                ?policy mms:scope morb: ;
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
                    baseIri = branchNode.uri,
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

            // clone graph
            run {
                // snapshot is available for source commit
                val sourceGraphs = transactionNode.listProperties(MMS.TXN.sourceGraph).toList()
                if(sourceGraphs.size >= 1) {
                    // copy graph
                    call.submitSparqlUpdate("""
                        copy graph <${sourceGraphs[0].`object`.asResource().uri}> to mor-graph:Staging.${context.transactionId} ;
                        
                        insert {
                            mor-snapshot:Staging.${context.transactionId} a mms:Staging ;
                                mms:ref morb: ;
                                mms:graph mor-graph:Staging.${context.transactionId} ;
                                .
                        }
                    """)

                    // copy staging => model
                    call.submitSparqlUpdate("""
                        copy mor-snapshot:Staging.${context.transactionId} mor-snapshot:Model.${context.transactionId} ;
                        
                        insert {
                            mor-snapshot:Model.${context.transactionId} a mms:Model ;
                                mms:ref morb: ;
                                mms:graph mor-graph:Model.${context.transactionId} ;
                                .
                        }
                    """)
                }
                // no snapshots available, must build for commit
                else {
                    TODO("build snapshot")
                }
            }

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
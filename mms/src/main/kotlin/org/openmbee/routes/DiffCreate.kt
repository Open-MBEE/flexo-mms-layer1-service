package org.openmbee.routes

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import org.apache.jena.rdf.model.impl.ResourceImpl
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.*
import java.security.MessageDigest


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

private fun hashString(input: String, algorithm: String): String {
    return MessageDigest
        .getInstance(algorithm)
        .digest(input.toByteArray())
        .fold("", { str, it -> str + "%02x".format(it) })
}


@OptIn(InternalAPI::class)
fun Application.createDiff() {
    routing {
        post("/orgs/{orgId}/repos/{repoId}/locks/{lockId}/diff") {
            val context = call.normalize {
                user()
                org()
                repo()
                commit()
                lock()
            }

            // ref prefixes
            val prefixes = context.prefixes

            // create a working model to prepare the Update
            val workingModel = KModel(prefixes)

            // create branch node
            val branchNode = workingModel.createResource(prefixes["morb"])

            // read put contents
            parseTurtle(
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
                removeAll(MMS.diffSrc)
                removeAll(MMS.diffDst)

                // normalize dct:title
                run {
                    // remove triples that don't point to literals
                    listProperties(DCTerms.title)
                        .forEach {
                            if(!it.`object`.isLiteral) {
                                workingModel.remove(it)
                            }
                        }

                    // assert ref triples
                    val refs = listProperties(MMS.ref).toList()
                    val sourceCount = refs.size
                    if(1 == sourceCount) {
                        refSource = if(!refs[0].`object`.isURIResource) {
                            throw Exception("Ref source must be an IRI")
                        } else {
                            refs[0].`object`.asResource().uri
                        }
                    }
                    else if(0 == sourceCount) {
                        throw Exception("Must specify a ref source using mms:ref predicate.")
                    }
                    else if(sourceCount > 1) {
                        throw Exception("Too many sources specified.")
                    }

                    removeAll(MMS.ref)
                    removeAll(MMS.commit)
                }

                addProperty(RDF.type, MMS.Diff)
                addProperty(MMS.diffSrc, ResourceImpl(prefixes["morcl"]))
                addProperty(MMS.createdBy, ResourceImpl(prefixes["mu"]))
            }

            val localConditions = DEFAULT_CONDITIONS.append {
                require("validSource") {
                    handler = { prefixes -> "Invalid ref source" }

                    """
                        graph mor-graph:Metadata {
                            ?_refSource a/rdfs:subClassOf* mms:Ref ;
                                mms:commit ?commitSource ;
                                . 
                           
                           ?commitSource a mms:Commit .
                        }
                    """
                }
            }

            // serialize lock node
            val diffTriples = KModel(prefixes) {
                add(branchNode.listProperties())
            }.stringify(emitPrefixes=false)

            // generate sparql update
            val sparqlUpdate = context.update {
                insert {
                    txn(
                        "mms-txn:diff" to "?diff",
                        "mms-txn:commitSource" to "?commitSource",
                        "mms-txn:diffInsGraph" to "?diffInsGraph",
                        "mms-txn:diffDelGraph" to "?diffDelGraph",
                    )

                    graph("?diffInsGraph") {
                        raw("""
                           ?ins_s ?ins_p ?ins_o . 
                        """)
                    }

                    graph("?diffDelGraph") {
                        raw("""
                           ?del_s ?del_p ?del_o . 
                        """)
                    }

                    graph("mor-graph:Metadata") {
                        raw("""
                            $diffTriples
                            
                            ?diff mms:id ?diffId ;
                                mms:diffSrc ?commitSource ;
                                mms:diffDst morc: ;
                                mms:insGraph ?diffInsGraph ;
                                mms:delGraph ?diffDelGraph ;
                                .
                        """)
                    }

                    // auto-policy
                    autoPolicySparqlBgp(
                        builder=this,
                        prefixes=prefixes,
                        scope=Scope.DIFF,
                        roles=listOf(Role.ADMIN_DIFF),
                    )
                }
                where {
                    raw(
                        *localConditions.requiredPatterns()
                    )

                    graph("?srcGraph") {
                        raw("""
                           ?src_s ?src_p ?src_o . 
                        """)
                    }

                    graph("?dstGraph") {
                        raw("""
                           ?dst_s ?dst_p ?dst_o . 
                        """)
                    }

                    graph("?srcGraph") {
                        raw("""
                           ?ins_s ?ins_p ?ins_o .
                            
                            minus {
                                ?dst_s ?dst_p ?dst_o .
                            }
                        """)
                    }

                    graph("?dstGraph") {
                        raw("""
                           ?del_s ?del_p ?del_o .
                            
                            minus {
                                ?src_s ?src_p ?src_o .
                            }
                        """)
                    }

                    graph("mor-graph:Metadata") {
                        raw("""
                            optional {
                                ?snapshot a/rdfs:subClassOf* mms:Snapshot ;
                                    mms:ref/mms:commit ?commitSource ;
                                    mms:graph ?sourceGraph ;
                                    .
                            }
                            
                            bind(
                                sha256(
                                    concat(str(morc:), "\n", str(?commitSource))
                                ) as ?diffId
                            )
                            
                            bind(
                                iri(
                                    concat(str(morc:), "/diffs/", ?diffId)
                                ) as ?diff
                            )
                            
                            bind(
                                iri(
                                    concat(str(mor-graph:), "Diff.Ins.", ?diffId)
                                ) as ?diffInsGraph
                            )
                            
                            bind(
                                iri(
                                    concat(str(mor-graph:), "Diff.Del.", ?diffId)
                                ) as ?diffDelGraph
                            )
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
                parseTurtle(
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
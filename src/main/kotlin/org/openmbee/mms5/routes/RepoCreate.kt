package org.openmbee.mms5.routes

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
import org.apache.jena.vocabulary.RDF
import org.openmbee.mms5.*

// default starting conditions for any calls to create a repo
private val DEFAULT_CONDITIONS = ORG_CRUD_CONDITIONS.append {
    // require that the user has the ability to create repos on a repo-level scope
    permit(Permission.CREATE_REPO, Scope.REPO)

    // require that the given repo does not exist before attempting to create it
    require("repoNotExists") {
        handler = { mms -> "The provided repo <${mms.prefixes["mor"]}> already exists." }

        """
            # repo must not yet exist
            graph m-graph:Cluster {
                filter not exists {
                    mor: a mms:Repo .
                }
            }
        """
    }

    // require that there is no pre-existing metadata graph associated with the given repo id
    require("repoMetadataGraphEmpty") {
        handler = { mms -> "The Metadata graph <${mms.prefixes["mor-graph"]}Metadata> is not empty." }

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

fun Route.createRepo() {
    put("/orgs/{orgId}/repos/{repoId}") {
        call.mmsL1(Permission.CREATE_REPO) {
            // set the default starting branch id
            branchId = "master"

            // parse the path params
            pathParams {
                org()
                repo(legal = true)
            }

            // process RDF body from user about this new repo
            val repoTriples = filterIncomingStatements("mor") {
                // relative to this org node
                repoNode().apply {
                    // sanitize statements
                    sanitizeCrudObject {
                        setProperty(RDF.type, MMS.Repo)
                        setProperty(MMS.id, repoId!!)
                        setProperty(MMS.etag, transactionId)
                        setProperty(MMS.org, orgNode())
                    }
                }
            }

            // inherit the default conditions
            val localConditions = DEFAULT_CONDITIONS

            // prep SPARQL UPDATE string
            val updateString = buildSparqlUpdate {
                insert {
                    txn {
                        autoPolicy(Scope.REPO, Role.ADMIN_REPO)
                    }

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
                                mms:body ""^^mms-datatype:sparql ;
                                .

                            morb: a mms:Branch ;
                                mms:id ?_branchId ;
                                mms:etag ?_branchEtag ;
                                mms:commit morc: ;
                                mms:snapshot ?_model, ?_staging ;
                                .
                            
                            ?_model a mms:Model ;
                                mms:graph ?_modelGraph ;
                                .
                            
                            ?_staging a mms:Staging ;
                                mms:graph ?_stagingGraph ;
                                .
                        """)
                    }

                    graph("m-graph:Graphs") {
                        raw("""
                            mor-graph:Metadata a mms:MetadataGraph .
                        """)
                    }
                }
                where {
                    raw(*localConditions.requiredPatterns())
                }
            }

            executeSparqlUpdate(updateString) {
                prefixes(prefixes)

                iri(
                    "_model" to "${prefixes["mor-snapshot"]}Model.${transactionId}",
                    "_modelGraph" to "${prefixes["mor-graph"]}Model.${transactionId}",
                    "_staging" to "${prefixes["mor-snapshot"]}Staging.${transactionId}",
                    "_stagingGraph" to "${prefixes["mor-graph"]}Staging.${transactionId}",
                )

                literal(
                    "_branchEtag" to "${transactionId}0",
                )
            }

            val constructString = buildSparqlQuery {
                construct {
                    txn()

                    raw("""
                        mor: ?mor_p ?mor_o .
                        
                        ?thing ?thing_p ?thing_o .
                        
                        ?m_s ?m_p ?m_o .
                    """)
                }
                where {
                    group {
                        txn()

                        raw("""
                            graph m-graph:Cluster {
                                mor: a mms:Repo ;
                                    ?mor_p ?mor_o .
                                       
                                optional {
                                    ?thing mms:repo mor: ; 
                                        ?thing_p ?thing_o .
                                }
                            }
                            
                            graph mor-graph:Metadata {
                                ?m_s ?m_p ?m_o .
                            }
                        """)
                    }
                    raw("""union ${localConditions.unionInspectPatterns()}""")
                }
            }

            val constructResponseText = executeSparqlConstructOrDescribe(constructString)

            validateTransaction(constructResponseText, localConditions)

            // respond
            call.respondText(constructResponseText, contentType = RdfContentTypes.Turtle)

            // delete transaction graph
            run {
                // prepare SPARQL DROP
                val dropResponseText = executeSparqlUpdate("""
                    delete where {
                        graph m-graph:Transactions {
                            mt: ?p ?o .
                        }
                    }
                """)

                // log response
                log.info(dropResponseText)
            }
        }
    }
}

package org.openmbee.mms5.routes

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
import org.apache.jena.vocabulary.RDF
import org.openmbee.mms5.*


private val SPARQL_CONSTRUCT_TRANSACTION: (conditions: ConditionsGroup)->String = { """
    construct  {
        ?thing ?thing_p ?thing_o .
        
        ?m_s ?m_p ?m_o .
        
    } where {
        {
  
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


private val DEFAULT_CONDITIONS = ORG_CRUD_CONDITIONS.append {
    permit(Permission.CREATE_REPO, Scope.REPO)

    require("collectionNotExists") {
        handler = { mms -> "The provided collection <${mms.prefixes["moc"]}> already exists." }

        """
            # repo must not yet exist
            graph m-graph:Cluster {
                filter not exists {
                    moc: a mms:Collection .
                }
            }
        """
    }

    require("collectionMetadataGraphEmpty") {
        handler = { mms -> "The Metadata graph <${mms.prefixes["moc-graph"]}Metadata> is not empty." }

        """
            # repo metadata graph must be empty
            graph moc-graph:Metadata {
                filter not exists {
                    ?e_s ?e_p ?e_o .
                }
            }
        """
    }


}

fun Route.createCollection() {
    put("/orgs/{orgId}/collections/{collectionId}") {
        call.mmsL1(Permission.CREATE_REPO) {
            branchId = "main"

            pathParams {
                org()
                collection(legal = true)
            }

            val collectionTriples = filterIncomingStatements("moc") {
                collectionNode().apply {
                    sanitizeCrudObject {
                        setProperty(RDF.type, MMS.Collection)
                        setProperty(MMS.id, collectionId!!)
                        setProperty(MMS.org, orgNode())
                    }
                }
            }

            val localConditions = DEFAULT_CONDITIONS

            val updateString = buildSparqlUpdate {
                insert {
                    txn {
                        autoPolicy(Scope.COLLECTION, Role.ADMIN_REPO)
                    }

                    graph("m-graph:Cluster") {
                        raw(collectionTriples)
                    }

                    graph("moc-graph:Metadata") {
                        raw("""
                            mocc: a mms:Commit ;
                                mms:parent rdf:nil ;
                                mms:submitted ?_now ;
                                mms:message ?_commitMessage ;
                                .
                        """)
                    }
                }
                where {
                    raw(*localConditions.requiredPatterns())
                    groupDns()
                }
            }

            executeSparqlUpdate(updateString)

            val constructString = buildSparqlQuery {
                construct {
                    txn()

                    raw("""
                        moc: ?moc_p ?moc_o .
                        
                        ?thing ?thing_p ?thing_o .
                        
                        ?m_s ?m_p ?m_o .
                    """)
                }
                where {
                    group {
                        txn()

                        raw("""
                            graph m-graph:Cluster {
                                moc: a mms:Collection ;
                                    ?moc_p ?moc_o .
                                       
                                optional {
                                    ?thing mms:collection moc: ; 
                                        ?thing_p ?thing_o .
                                }
                            }
                            
                            graph moc-graph:Metadata {
                                ?m_s ?m_p ?m_o .
                            }
                        """)
                    }
                    raw("""union ${localConditions.unionInspectPatterns()}""")
                    groupDns()
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

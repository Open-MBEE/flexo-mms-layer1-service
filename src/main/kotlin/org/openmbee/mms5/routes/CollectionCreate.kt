package org.openmbee.mms5.routes

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.apache.jena.vocabulary.RDF
import org.openmbee.mms5.*


// default starting conditions for any calls to create a collection
private val DEFAULT_CONDITIONS = ORG_CRUD_CONDITIONS.append {
    // require that the user has the ability to create collections on an org-level scope
    permit(Permission.CREATE_COLLECTION, Scope.ORG)

    // require that the given collection does not exist before attempting to create it
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

    // require that there is no pre-existing metadata graph associated with the given collection id
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
        call.mmsL1(Permission.CREATE_COLLECTION) {
            // set the default starting branch id
            branchId = DEFAULT_BRANCH_ID

            // parse the path params
            pathParams {
                org()
                collection(legal = true)
            }

            // process RDF body from user about this new collection
            val collectionTriples = filterIncomingStatements("moc") {
                // relative to this org node
                collectionNode().apply {
                    // sanitize statements
                    sanitizeCrudObject {
                        setProperty(RDF.type, MMS.Collection)
                        setProperty(MMS.id, collectionId!!)
                        setProperty(MMS.org, orgNode())
                    }
                }
            }

            // inherit the default conditions
            val localConditions = DEFAULT_CONDITIONS

            // prep SPARQL UPDATE string
            val updateString = buildSparqlUpdate {
                insert {
                    // create a new txn object in the transactions graph
                    txn {
                        // create a new policy that grants this user admin over the new collection
                        autoPolicy(Scope.COLLECTION, Role.ADMIN_COLLECTION)
                    }

                    // insert the triples about the new collection, including arbitrary metadata supplied by user
                    graph("m-graph:Cluster") {
                        raw(collectionTriples)
                    }

                    // initialize the collection metadata graph
                    graph("moc-graph:Metadata") {
                        raw("""
                            # root commit
                            mocc: a mms:Commit ;
                                mms:parent rdf:nil ;
                                mms:submitted ?_now ;
                                mms:message ?_commitMessage ;
                                mms:collects () ;
                                .

                            # default branch
                            morb: a mms:Branch ;
                                mms:id ?_branchId ;
                                mms:etag ?_branchEtag ;
                                mms:commit morc: ;
                                .
                        """)
                    }

                    // declare new graph
                    graph("m-graph:Graphs") {
                        raw("""
                            moc-graph:Metadata a mms:CollectionMetadataGraph .
                        """)
                    }
                }
                where {
                    // assert the required conditions (e.g., access-control, existence, etc.)
                    raw(*localConditions.requiredPatterns())
                }
            }

            // execute update
            executeSparqlUpdate(updateString)

            // create construct query to confirm transaction and fetch collection details
            val constructString = buildSparqlQuery {
                construct {
                    // all the details about this transaction
                    txn()

                    // all the properties about this repo
                    raw("""
                        # outgoing collection properties
                        moc: ?moc_p ?moc_o .
                        
                        # properties of things that belong to this collection
                        ?thing ?thing_p ?thing_o .
                        
                        # all triples in metadata graph
                        ?m_s ?m_p ?m_o .
                    """)
                }
                where {
                    // first group in a series of unions fetches intended outputs
                    group {
                        txn(null, "moc")

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
                    // all subsequent unions are for inspecting what if any conditions failed
                    raw("""union ${localConditions.unionInspectPatterns()}""")
                }
            }

            // execute construct
            val constructResponseText = executeSparqlConstructOrDescribe(constructString)

            // validate whether the transaction succeeded
            val constructModel = validateTransaction(constructResponseText, localConditions, null, "moc")

            // check that the user-supplied HTTP preconditions were met
            handleEtagAndPreconditions(constructModel, prefixes["moc"])

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

package org.openmbee.flexo.mms.routes.ldp

import io.ktor.http.*
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.server.LdpDcLayer1Context
import org.openmbee.flexo.mms.server.LdpMutateResponse
import java.util.*


// require that the given repo does not exist before attempting to create it
private fun ConditionsBuilder.repoNotExists() {
    // require that the given repo does not exist before attempting to create it
    require("repoNotExists") {
        handler = { mms -> "The provided repo <${mms.prefixes["mor"]}> already exists." to HttpStatusCode.Conflict }

        """
            # repo must not yet exist
            filter not exists {
                graph m-graph:Cluster {
                    mor: a mms:Repo .
                }
            }
        """
    }

    // require that the metadata graph does not exist before attempting to create it
    require("repoMetadataGraphNotExists") {
        handler = { mms -> "The Metadata graph <${mms.prefixes["mor-graph"]}Metadata> already exists." to HttpStatusCode.Conflict }

        """
            # metadata graph must not yet exist
            filter not exists {
                graph m-graph:Graphs {
                    mor-graph:Metadata a mms:RepoMetadataGraph .
                }
            }
        """
    }

    // require that there is no pre-existing metadata graph associated with the given repo id
    require("repoMetadataGraphEmpty") {
        handler = { mms -> "The Metadata graph <${mms.prefixes["mor-graph"]}Metadata> is not empty." to HttpStatusCode.Conflict }

        """
            # repo metadata graph must be empty
            filter not exists {
                graph mor-graph:Metadata {
                    ?e_s ?e_p ?e_o .
                }
            }
        """
    }
}


// selects all properties of an existing repo
private fun PatternBuilder<*>.existingRepo() {
    graph("m-graph:Cluster") {
        raw("""
            mor: ?repoExisting_p ?repoExisting_o .
        """)
    }
}

/**
 * Creates or replaces org(s)
 *
 * TResponseContext generic is bound by LdpWriteResponse, which can be a response to either a PUT or POST request
 */
suspend fun <TResponseContext: LdpMutateResponse> LdpDcLayer1Context<TResponseContext>.createOrReplaceRepo() {
    // process RDF body from user about this new repo
    val repoTriples = filterIncomingStatements("mor") {
        // relative to this repo node
        repoNode().apply {
            // sanitize statements
            sanitizeCrudObject {
                setProperty(RDF.type, MMS.Repo)
                setProperty(MMS.id, repoId!!)
                setProperty(MMS.etag, transactionId)
                setProperty(MMS.org, orgNode())
                setProperty(MMS.defaultBranchId, defaultBranchId)
            }
        }
    }

    // set branch to create
    branchId = defaultBranchId

    // resolve ambiguity
    if(intentIsAmbiguous) {
        // ask if repo exists
        val probeResults = executeSparqlSelectOrAsk(buildSparqlQuery {
            ask {
                existingRepo()
            }
        })

        // parse response
        val exists = parseSparqlResultsJsonAsk(probeResults)

        // repo does not exist
        if(!exists) {
            replaceExisting = false
        }
    }

    // build conditions
    val localConditions = ORG_CRUD_CONDITIONS.append {
        // POST
        if(isPostMethod) {
            // user is asking to create repo only if the state of its container org passes their preconditions
            appendPreconditions { values ->
                """
                    graph m-graph:Cluster {
                        mo: mms:etag ?__mms_etag .

                        $values
                    }
                """
            }
        }
        // not POST
        else {
            // resource must exist
            if(mustExist) {
                repoExists()
            }

            // resource must not exist
            if(mustNotExist) {
                repoNotExists()
            }
            // resource may exist
            else {
                // enforce preconditions if present
                appendPreconditions { values ->
                    """
                        graph m-graph:Cluster {
                            ${if(mustExist) "" else "optional {"}
                                mor: mms:etag ?__mms_etag .
                                $values
                            ${if(mustExist) "" else "}"}
                        }                        
                    """
                }
            }
        }

        // resource is being replaced
        if(replaceExisting) {
            // require that the user has the ability to update repos on a repo-level scope (necessarily implies ability to create)
            permit(Permission.UPDATE_REPO, Scope.ORG)
        }
        // resource is being created
        else {
            // require that the user has the ability to create repos on an org-level scope
            permit(Permission.CREATE_REPO, Scope.ORG)
        }
    }

    // prep SPARQL UPDATE string
    val updateString = buildSparqlUpdate {
        if(replaceExisting) {
            delete {
                existingRepo()
            }
        }
        insert {
            // create a new txn object in the transactions graph
            txn {
                // not replacing existing, create new policy
                if(!replaceExisting) {
                    // create a new policy that grants this user admin over the new repo
                    autoPolicy(Scope.REPO, Role.ADMIN_REPO)

                    // create similar policy for master branch
                    autoPolicy(Scope.BRANCH, Role.ADMIN_BRANCH)
                }
            }

            // insert the triples about the new repo, including arbitrary metadata supplied by user
            graph("m-graph:Cluster") {
                raw(repoTriples)
            }

            // not replacing existing
            if(!replaceExisting) {
                // initialize the repo metadata graph
                graph("mor-graph:Metadata") {
                    raw("""
                        # root commit
                        morc: a mms:Commit ;
                            mms:parent rdf:nil ;
                            mms:submitted ?_now ;
                            mms:message ?_commitMessage ;
                            mms:data morc-data: ;
                            .
                
                        # root commit data
                        morc-data: a mms:Load ;
                            mms:body ""^^mms-datatype:sparql ;
                            .

                        # default branch
                        morb: a mms:Branch ;
                            mms:id ?_branchId ;
                            mms:etag ?_branchEtag ;
                            mms:commit morc: ;
                            mms:snapshot ?_staging ;
                            dct:title "Master"@en ;
                            .
                            
                        # model snapshot
                        mor-lock:Commit.root a mms:Lock ;
                            mms:commit morc: ;
                            mms:snapshot ?_model ;
                            .
                        
                        # initial model graph
                        ?_model a mms:Model ;
                            mms:graph ?_modelGraph ;
                            .
                        
                        # initial staging graph
                        ?_staging a mms:Staging ;
                            mms:graph ?_stagingGraph ;
                            .
                    """)
                }

                // declare new graphs
                graph("m-graph:Graphs") {
                    raw("""
                        mor-graph:Metadata a mms:RepoMetadataGraph .
                        
                        ?_stagingGraph a mms:SnapshotGraph .
                        
                        ?_modelGraph a mms:SnapshotGraph .
                    """)
                }
            }
        }
        where {
            // assert the required conditions (e.g., access-control, existence, etc.)
            raw(*localConditions.requiredPatterns())
        }
    }

    // execute update
    executeSparqlUpdate(updateString) {
        // provide explicit prefix map
        prefixes(prefixes)

        // replace IRI substitution variables
        iri(
            "_model" to "${prefixes["mor-snapshot"]}Model.${transactionId}",
            "_modelGraph" to "${prefixes["mor-graph"]}Model.${transactionId}",
            "_staging" to "${prefixes["mor-snapshot"]}Staging.${transactionId}",
            "_stagingGraph" to "${prefixes["mor-graph"]}Staging.${transactionId}",
        )

        // replace Literal substitution variables
        literal(
            // append "0" to commit etag in order to distinguish between repo etag
            "_commitEtag" to "${transactionId}0",

            // append "1" to branch etag in order to distinguish between repo etag
            "_branchEtag" to "${transactionId}1",
        )
    }

    // create construct query to confirm transaction and fetch repo details
    val constructString = buildSparqlQuery {
        construct {
            // all the details about this transaction
            txn()

            // all the properties about this repo
            raw("""
                # outgoing repo properties
                mor: ?mor_p ?mor_o .
                
                # properties of things that belong to this repo
                ?thing ?thing_p ?thing_o .
                
                # all triples in metadata graph
                ?m_s ?m_p ?m_o .
                
                # metadata element etags
                <${MMS_URNS.SUBJECT.aggregator}> mms:etag ?elementEtag .
            """)
        }
        where {
            // first group in a series of unions fetches intended outputs
            group {
                txn(null, "mor")

                // include the AutoBranchOwner policy
                auth("morb")

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
                        
                        optional {
                            ?m_s mms:etag ?elementEtag
                        }
                    }
                """)
            }
            // all subsequent unions are for inspecting what if any conditions failed
            raw("""union ${localConditions.unionInspectPatterns()}""")
        }
    }

    // finalize transaction
    finalizeMutateTransaction(constructString, localConditions, "mor", !replaceExisting)
}

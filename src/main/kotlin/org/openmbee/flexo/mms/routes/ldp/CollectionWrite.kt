package org.openmbee.flexo.mms.routes.ldp

import io.ktor.http.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.server.LdpDcLayer1Context
import org.openmbee.flexo.mms.server.LdpMutateResponse


// require that the given collection does not exist before attempting to create it
private fun ConditionsBuilder.collectionNotExists() {
    require("collectionNotExists") {
        handler = { layer1 -> "The provided collection <${layer1.prefixes["moc"]}> already exists." to HttpStatusCode.Conflict }

        """
            # collection must not yet exist
            filter not exists {
                graph m-graph:Cluster {
                    moc: a mms:Collection .
                }
            }
        """
    }
}

// selects all properties of an existing collection
private fun PatternBuilder<*>.existingCollection(filterCreate: Boolean = false) {
    graph("m-graph:Cluster") {
        raw("""
            moc: ?collectionExisting_p ?collectionExisting_o .
        """)
        if (filterCreate) {
            raw("""
                filter(?collectionExisting_p != mms:created)
                filter(?collectionExisting_p != mms:createdBy)
            """.trimIndent())
        }
    }
}

/**
 * Creates or replaces collection(s)
 *
 * Collections are lightweight — stored only in m-graph:Cluster with mms:collects triples
 * pointing to validated refs (branches/locks). No separate metadata graph with commits/branches.
 */
suspend fun <TResponseContext: LdpMutateResponse> LdpDcLayer1Context<TResponseContext>.createOrReplaceCollection() {
    // process RDF body from user about this new collection
    val collectionTriples = filterIncomingStatements("moc") {
        // relative to this collection node
        collectionNode().apply {
            // sanitize statements
            sanitizeCrudObject {
                setProperty(RDF.type, MMS.Collection)
                setProperty(MMS.id, collectionId!!)
                setProperty(MMS.org, orgNode())
                setProperty(MMS.etag, layer1.transactionId, true)
                bypass(MMS.collects)
            }

            // extract mms:collects URIs from the body
            val collectsResources = extract1OrMoreUris(MMS.collects)

            // remove the mms:collects statements from sanitized triples (they'll be re-inserted with absolute URIs)
            listProperties(MMS.collects).toList().forEach { it.remove() }

            // store resolved collects URIs for later use
            layer1.requestContext.call.attributes.put(COLLECTS_URIS_KEY, collectsResources.map { it.uri })
        }
    }

    // retrieve the resolved collects URIs
    val collectsUris = call.attributes[COLLECTS_URIS_KEY]

    // Step 1: Verify all refs exist and determine their types
    val valuesClause = collectsUris.joinToString(" ") { "<$it>" }

    val refExistenceQuery = """
        select ?ref ?refType where {
            values ?ref { $valuesClause }
            #graph m-graph:Cluster { ## having the filter and binding doesn't work and returns nothing?
            #    ?repo a mms:Repo .
            #    filter(strstarts(str(?ref), concat(str(?repo), "/")))
            #}
            # derive the repo metadata graph IRI from the repo IRI
            #bind(iri(concat(str(?repo), "/graphs/Metadata")) as ?repoMetaGraph)
            graph ?repoMetaGraph {
                ?ref a ?refType .
                filter(?refType in (mms:Branch, mms:Lock, mms:Scratch))
            }
        }
    """.trimIndent()

    val existenceResults = executeSparqlSelectOrAsk(refExistenceQuery) {
        prefixes(prefixes)
    }
    val existenceBindings = parseSparqlResultsJsonSelect(existenceResults)
    val foundRefTypes = existenceBindings.mapNotNull { binding ->
        val ref = binding["ref"]?.jsonObject?.get("value")?.jsonPrimitive?.content
        val refType = binding["refType"]?.jsonObject?.get("value")?.jsonPrimitive?.content
        if (ref != null && refType != null) ref to refType else null
    }.toMap()

    for (uri in collectsUris) {
        if (uri !in foundRefTypes) {
            throw Http404Exception("Ref <$uri> does not exist")
        }
    }

    // Step 2: Verify admin permission on each ref, correlating ref type with required permission
    // Derive repo and org scope URIs from the ref URI to avoid the Cluster graph lookup
    // (Fuseki does not reliably evaluate filter(strstarts) with VALUES-bound variables)
    val clusterUri = prefixes["m"]!!
    val refPermissionValues = foundRefTypes.entries.joinToString("\n") { (ref, refType) ->
        val requiredPerm = when {
            refType.endsWith("Branch") -> "mms-object:Permission.DeleteBranch"
            refType.endsWith("Lock") -> "mms-object:Permission.DeleteLock"
            refType.endsWith("Scratch") -> "mms-object:Permission.DeleteScratch"
            else -> throw Http400Exception("Unknown ref type: $refType")
        }
        val repoUri = ref.replace(Regex("/(branches|locks|scratches)/.*$"), "")
        val orgUri = repoUri.replace(Regex("/repos/.*$"), "")
        "( <$ref> $requiredPerm <$repoUri> <$orgUri> <$clusterUri> )"
    }

    val permissionQuery = """
        select ?ref where {
            values (?ref ?requiredPerm ?repoScope ?orgScope ?clusterScope) { $refPermissionValues }
            
            graph m-graph:AccessControl.Policies {
                ?policy a mms:Policy ;
                    mms:scope ?scope ;
                    mms:role ?role .
            }
            
            {
                graph m-graph:AccessControl.Policies {
                    ?policy mms:subject mu: .
                }
            } union {
                graph m-graph:AccessControl.Agents {
                    ?group a mms:Group ;
                        mms:id ?groupId .
                    values ?groupId {
                        # @values groupId
                    }
                }
                graph m-graph:AccessControl.Policies {
                    ?policy mms:subject ?group .
                }
            }
            
            filter(?scope = ?ref || ?scope = ?repoScope || ?scope = ?orgScope || ?scope = ?clusterScope)
            
            graph m-graph:AccessControl.Definitions {
                ?role a mms:Role ;
                    mms:permits ?permission .
                ?permission a mms:Permission ;
                    mms:implies* ?requiredPerm .
            }
        }
    """.trimIndent()

    val permissionResults = executeSparqlSelectOrAsk(permissionQuery) {
        prefixes(prefixes)
    }
    val permissionBindings = parseSparqlResultsJsonSelect(permissionResults)
    val permittedRefs = permissionBindings.mapNotNull { it["ref"]?.jsonObject?.get("value")?.jsonPrimitive?.content }.toSet()

    for (uri in collectsUris) {
        if (uri !in permittedRefs) {
            throw Http403Exception(this, "Ref <$uri>")
        }
    }

    // resolve ambiguity
    if(intentIsAmbiguous) {
        // ask if collection exists
        val probeResults = executeSparqlSelectOrAsk(buildSparqlQuery {
            ask {
                existingCollection()
            }
        })

        // parse response
        val exists = parseSparqlResultsJsonAsk(probeResults)

        // collection does not exist
        if(!exists) {
            replaceExisting = false
        }
    }

    // intent is ambiguous or resource is definitely being replaced
    val permission = if(replaceExisting) {
        Permission.UPDATE_COLLECTION
    } else {
        Permission.CREATE_COLLECTION
    }

    // build conditions
    val localConditions = ORG_CRUD_CONDITIONS.append {
        if(isPostMethod) {
            // reject preconditions on POST
            if(ifMatch != null || ifNoneMatch != null) {
                throw PreconditionsForbidden("when creating collection via POST")
            }
        } else {
            // resource must exist
            if(mustExist) {
                collectionExists()
            }

            // resource must not exist
            if(mustNotExist) {
                collectionNotExists()
            } else {
                // enforce preconditions if present
                appendPreconditions { values ->
                    """
                        graph m-graph:Cluster {
                            ${if(mustExist) "" else "optional {"}
                                moc: mms:etag ?__mms_etag .
                                ${values.reindent(8)}
                            ${if(mustExist) "" else "}"}
                        }
                    """
                }
            }
        }

        // apply relevant permission
        permit(permission, if(replaceExisting) Scope.COLLECTION else Scope.ORG)
    }

    // build mms:collects insert triples
    val collectsTriples = collectsUris.joinToString("\n") { uri ->
        "moc: mms:collects <$uri> ."
    }

    // prep SPARQL UPDATE string
    val updateString = buildSparqlUpdate {
        if(replaceExisting) {
            delete {
                existingCollection()
            }
        }
        insert {
            // create a new txn object in the transactions graph
            txn {
                // create a new policy that grants this user admin over the new collection
                if(!replaceExisting) autoPolicy(Scope.COLLECTION, Role.ADMIN_COLLECTION)

                // write whether this action replaces an existing resource to the transaction
                replacesExisting(replaceExisting)
            }

            // insert the triples about the collection, including arbitrary metadata supplied by user
            graph("m-graph:Cluster") {
                raw(collectionTriples)
                raw(collectsTriples)
                if (!replaceExisting) {
                    raw("""
                        moc: mms:created ?_now ;
                            mms:createdBy mu: .
                    """)
                }
            }
        }
        where {
            if (replaceExisting) {
                existingCollection(true)
            }
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

            // all the properties about this collection
            raw("""
                moc: ?moc_p ?moc_o .
            """)
        }
        where {
            txnOrInspections(null, localConditions) {
                raw("""
                    # extract the created/updated collection properties
                    graph m-graph:Cluster {
                        moc: ?moc_p ?moc_o .
                    }
                """)
            }
        }
    }

    // finalize transaction
    finalizeMutateTransaction(constructString, localConditions, "moc", !replaceExisting)
}

// attribute key for passing collects URIs between filterIncomingStatements and the rest of the handler
val COLLECTS_URIS_KEY = io.ktor.util.AttributeKey<List<String>>("collects_uris")

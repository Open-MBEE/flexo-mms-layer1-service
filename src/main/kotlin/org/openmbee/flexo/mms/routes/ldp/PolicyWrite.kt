package org.openmbee.flexo.mms.routes.ldp

import io.ktor.http.*
import io.ktor.server.response.*
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.server.LdpDcLayer1Context
import org.openmbee.flexo.mms.server.LdpMutateResponse


// require that the given policy does not exist before attempting to create it
private fun ConditionsBuilder.policyNotExists() {
    require("policyNotExists") {
        handler = { mms -> "The provided policy <${mms.prefixes["mp"]}> already exists." to HttpStatusCode.Conflict }

        """
            # destination policy must not yet exist
            graph m-graph:AccessControl.Policies {
                filter not exists {
                    mp: a mms:Policy .
                }
            }
        """
    }
}

// selects all properties of an existing policy
private fun PatternBuilder<*>.existingPolicy() {
    graph("m-graph:AccessControl.Policies") {
        raw("""
            mp: ?policyExisting_p ?policyExisting_o .
        """)
    }
}

// resolved scope information for a policy's target
private data class PolicyScopeAuthorization(
    val permission: Permission,
    val scope: Scope,
    val ancestorScopeUris: List<String>,
)

// inspect the policy's target scope URI and resolve which DELETE permission and ancestor scopes apply.
// the prefixes for a policy creation request only include cluster/user/policy, so we cannot rely on
// `Scope.values()` (which expects e.g. `mo:`/`mor:` to be defined) and instead pass explicit URIs to
// `permit(...)` via its `scopeUris` parameter.
private fun resolvePolicyScopeAuthorization(scopeUri: String, clusterUri: String): PolicyScopeAuthorization {
    val rest = if(scopeUri.startsWith(clusterUri)) scopeUri.substring(clusterUri.length) else null

    if(scopeUri == clusterUri || rest == "") {
        // cluster-scoped policies still require cluster-level admin (UPDATE_POLICY) as before
        return PolicyScopeAuthorization(Permission.UPDATE_POLICY, Scope.CLUSTER, listOf(clusterUri))
    }

    if(rest == null) {
        throw Http400Exception("Invalid policy scope <$scopeUri>: not a recognized resource under <$clusterUri>")
    }

    val branchMatch = Regex("""^orgs/([^/]+)/repos/([^/]+)/branches/([^/]+)$""").matchEntire(rest)
    if(branchMatch != null) {
        val (orgId, repoId, _) = branchMatch.destructured
        return PolicyScopeAuthorization(
            Permission.DELETE_BRANCH, Scope.BRANCH,
            listOf(clusterUri, "${clusterUri}orgs/$orgId", "${clusterUri}orgs/$orgId/repos/$repoId", scopeUri),
        )
    }

    val lockMatch = Regex("""^orgs/([^/]+)/repos/([^/]+)/locks/([^/]+)$""").matchEntire(rest)
    if(lockMatch != null) {
        val (orgId, repoId, _) = lockMatch.destructured
        return PolicyScopeAuthorization(
            Permission.DELETE_LOCK, Scope.LOCK,
            listOf(clusterUri, "${clusterUri}orgs/$orgId", "${clusterUri}orgs/$orgId/repos/$repoId", scopeUri),
        )
    }

    val scratchMatch = Regex("""^orgs/([^/]+)/repos/([^/]+)/scratches/([^/]+)$""").matchEntire(rest)
    if(scratchMatch != null) {
        val (orgId, repoId, _) = scratchMatch.destructured
        return PolicyScopeAuthorization(
            Permission.DELETE_SCRATCH, Scope.SCRATCH,
            listOf(clusterUri, "${clusterUri}orgs/$orgId", "${clusterUri}orgs/$orgId/repos/$repoId", scopeUri),
        )
    }

    val repoMatch = Regex("""^orgs/([^/]+)/repos/([^/]+)$""").matchEntire(rest)
    if(repoMatch != null) {
        val (orgId, _) = repoMatch.destructured
        return PolicyScopeAuthorization(
            Permission.DELETE_REPO, Scope.REPO,
            listOf(clusterUri, "${clusterUri}orgs/$orgId", scopeUri),
        )
    }

    val collectionMatch = Regex("""^orgs/([^/]+)/collections/([^/]+)$""").matchEntire(rest)
    if(collectionMatch != null) {
        val (orgId, _) = collectionMatch.destructured
        return PolicyScopeAuthorization(
            Permission.DELETE_COLLECTION, Scope.COLLECTION,
            listOf(clusterUri, "${clusterUri}orgs/$orgId", scopeUri),
        )
    }

    val orgMatch = Regex("""^orgs/([^/]+)$""").matchEntire(rest)
    if(orgMatch != null) {
        return PolicyScopeAuthorization(
            Permission.DELETE_ORG, Scope.ORG,
            listOf(clusterUri, scopeUri),
        )
    }

    throw Http400Exception("Invalid policy scope <$scopeUri>: does not match any known scope pattern")
}

suspend fun <TResponseContext: LdpMutateResponse> LdpDcLayer1Context<TResponseContext>.createOrReplacePolicy() {
    // parse the path params
    parsePathParams {
        policy(legal=true)
    }

    // captured for use after the body is parsed; the policy's target scope URI determines which
    // admin role the requesting user must hold to create/update this policy.
    lateinit var scopeUri: String

    // process RDF body from user about this new policy
    val policyTriples = filterIncomingStatements("mp") {
        // relative to this policy node
        policyNode().apply {
            // expect exactly 1 subject node
            val subjectNode = extractExactly1Uri(MMS.subject)

            // expect exactly 1 scope node
            val scopeNode = extractExactly1Uri(MMS.scope)
            scopeUri = scopeNode.uri

            // expect 1 or more roles
            val roleNodes = extract1OrMoreUris(MMS.role)

            // sanitize statements
            sanitizeCrudObject {
                setProperty(RDF.type, MMS.Policy)
                setProperty(MMS.id, policyId!!)
                setProperty(MMS.etag, transactionId)
                bypass(MMS.subject)
                bypass(MMS.scope)
                bypass(MMS.role)
            }
        }
    }

    // resolve ambiguity
    if(intentIsAmbiguous) {
        // ask if policy exists
        val probeResults = executeSparqlSelectOrAsk(buildSparqlQuery {
            ask {
                existingPolicy()
            }
        })

        // parse response
        val exists = parseSparqlResultsJsonAsk(probeResults)

        // policy does not exist
        if(!exists) {
            replaceExisting = false
        }
    }

    // inherit the default conditions
    val localConditions = GLOBAL_CRUD_CONDITIONS.append {
        // POST
        if (isPostMethod) {
            // reject preconditions on POST; ETags not created for cluster since that would degrade multi-tenancy
            if (ifMatch != null || ifNoneMatch != null) {
                throw PreconditionsForbidden("when creating policy via POST")
            }
        }
        // not POST
        else {
            // resource must exist
            if (mustExist) {
                policyExists()
            }

            // resource must not exist
            if (mustNotExist) {
                policyNotExists()
            }
            // resource may exist
            else {
                // enforce preconditions if present
                appendPreconditions { values ->
                    """
                        graph m-graph:AccessControl.Policies {
                            ${if (mustExist) "" else "optional {"}
                                mp: mms:etag ?__mms_etag .
                                ${values.reindent(8)}
                            ${if (mustExist) "" else "}"}
                        }
                    """
                }
            }
        }

        // require that the user has Admin role (i.e. DELETE permission) on the policy's target scope.
        // the same check applies to both create and update — anyone authorized to grant access at this
        // scope must already be an admin of it.
        val auth = resolvePolicyScopeAuthorization(scopeUri, prefixes["m"]!!)
        permit(auth.permission, auth.scope, auth.ancestorScopeUris)
    }

    // prep SPARQL UPDATE string
    val updateString = buildSparqlUpdate {
        if(replaceExisting) {
            delete {
                existingPolicy()
            }
        }
        insert {
            // create a new txn object in the transactions graph
            txn {
//                // create a new policy that grants this user admin over the new policy
//                if(!replaceExisting) autoPolicy(Scope.POLICY, Role.ADMIN_POLICY)
            }

            // insert the triples about the new policy, including arbitrary metadata supplied by user
            graph("m-graph:AccessControl.Policies") {
                raw(policyTriples)
            }
        }
        where {
            // assert the required conditions (e.g., access-control, existence, etc.)
            raw(*localConditions.requiredPatterns())
        }
    }

    // execute update
    executeSparqlUpdate(updateString)

    // create construct query to confirm transaction and fetch policy details
    val constructString = buildSparqlQuery {
        construct {
            // all the details about this transaction
            txn()

            // all the properties about this policy
            raw("""
                mp: ?mp_p ?mp_o .
            """)
        }
        where {
            // first group in a series of unions fetches intended outputs
            group {
                txn()

                raw("""
                    graph m-graph:AccessControl.Policies {
                        mp: ?mp_p ?mp_o .
                    }
                """)
            }
            // all subsequent unions are for inspecting what if any conditions failed
            raw("""union ${localConditions.unionInspectPatterns()}""")
        }
    }

    // finalize transaction
    finalizeMutateTransaction(constructString, localConditions, "mp", !replaceExisting)
}

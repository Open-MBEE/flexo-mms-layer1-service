package org.openmbee.flexo.mms

val LDAP_COMPATIBLE_SLUG_REGEX = """[/?&=,._\pL0-9-]{3,256}""".toRegex()

enum class Crud(val id: String) {
    CREATE("Create"),
    READ("Read"),
    UPDATE("Update"),
    DELETE("Delete"),
}

enum class Scope(val type: String, val id: String, vararg val extras: String) {
    CLUSTER("Cluster", "m"),
    ORG("Org", "mo"),
    COLLECTION("Collection", "moc"),
    REPO("Repo", "mor"),
    BRANCH("Branch", "morb"),
    LOCK("Lock", "morl"),
    ARTIFACT("Artifact", "mora"),
    SCRATCH("Scratch", "mors"),
    DIFF("Diff", "mord"),
    COMMIT("Commit", "morc"),

    ACCESS_CONTROL_ANY("AccessControl", "ma", "ma:Agents", "ma:Policies"),
    USER("User", "mu"),
    GROUP("Group", "mg"),
    POLICY("Policy", "mp"),
}

fun Scope.values() = sequence<String> {
    for(i in 1..id.length) {
        yield(id.substring(0, i)+":")
    }

    for(extra in extras) {
        yield(extra)
    }
}

enum class Permission(
    val crud: Crud,
    val scope: Scope,
    val id: String ="${crud.id}${scope.type}",
) {
    CREATE_ORG(Crud.CREATE, Scope.ORG),
    READ_ORG(Crud.READ, Scope.ORG),
    UPDATE_ORG(Crud.UPDATE, Scope.ORG),
    DELETE_ORG(Crud.DELETE, Scope.ORG),

    CREATE_COLLECTION(Crud.CREATE, Scope.COLLECTION),
    READ_COLLECTION(Crud.READ, Scope.COLLECTION),
    UPDATE_COLLECTION(Crud.UPDATE, Scope.COLLECTION),
    DELETE_COLLECTION(Crud.DELETE, Scope.COLLECTION),

    CREATE_REPO(Crud.CREATE, Scope.REPO),
    READ_REPO(Crud.READ, Scope.REPO),
    UPDATE_REPO(Crud.UPDATE, Scope.REPO),
    DELETE_REPO(Crud.DELETE, Scope.REPO),

    CREATE_BRANCH(Crud.CREATE, Scope.BRANCH),
    READ_BRANCH(Crud.READ, Scope.BRANCH),
    UPDATE_BRANCH(Crud.UPDATE, Scope.BRANCH),
    DELETE_BRANCH(Crud.DELETE, Scope.BRANCH),

    CREATE_LOCK(Crud.CREATE, Scope.LOCK),
    READ_LOCK(Crud.READ, Scope.LOCK),
    UPDATE_LOCK(Crud.UPDATE, Scope.LOCK),
    DELETE_LOCK(Crud.DELETE, Scope.LOCK),

    CREATE_ARTIFACT(Crud.CREATE, Scope.ARTIFACT),
    READ_ARTIFACT(Crud.READ, Scope.ARTIFACT),
    UPDATE_ARTIFACT(Crud.UPDATE, Scope.ARTIFACT),
    DELETE_ARTIFACT(Crud.DELETE, Scope.ARTIFACT),

    CREATE_SCRATCH(Crud.CREATE, Scope.SCRATCH),
    READ_SCRATCH(Crud.READ, Scope.SCRATCH),
    UPDATE_SCRATCH(Crud.UPDATE, Scope.SCRATCH),
    DELETE_SCRATCH(Crud.DELETE, Scope.SCRATCH),
    
    READ_COMMIT(Crud.READ, Scope.COMMIT),
    UPDATE_COMMIT(Crud.UPDATE, Scope.COMMIT),

    CREATE_DIFF(Crud.CREATE, Scope.DIFF),
    READ_DIFF(Crud.READ, Scope.DIFF),
    UPDATE_DIFF(Crud.UPDATE, Scope.DIFF),
    DELETE_DIFF(Crud.DELETE, Scope.DIFF),

    CREATE_GROUP(Crud.CREATE, Scope.GROUP),
    READ_GROUP(Crud.READ, Scope.GROUP),
    UPDATE_GROUP(Crud.UPDATE, Scope.GROUP),
    DELETE_GROUP(Crud.DELETE, Scope.GROUP),

    CREATE_POLICY(Crud.CREATE, Scope.POLICY),
    READ_POLICY(Crud.READ, Scope.POLICY),
    UPDATE_POLICY(Crud.UPDATE, Scope.POLICY),
    DELETE_POLICY(Crud.DELETE, Scope.POLICY),
}


enum class Role(val id: String) {
    ADMIN_ORG("AdminOrg"),
    ADMIN_REPO("AdminRepo"),
    ADMIN_COLLECTION("AdminCollection"),
    ADMIN_METADATA("AdminMetadata"),
    ADMIN_MODEL("AdminModel"),
    ADMIN_LOCK("AdminLock"),
    ADMIN_BRANCH("AdminBranch"),
    ADMIN_SCRATCH("AdminScratch"),
    ADMIN_DIFF("AdminDiff"),
    ADMIN_GROUP("AdminGroup"),
    ADMIN_POLICY("AdminPolicy"),
}

/**
 * The IRI of this role as referenced by policies, matching what [autoPolicy] emits
 * (`mms-object:Role.{id}`) and what the cluster init definitions declare.
 */
val Role.iri: String
    get() = "${SPARQL_PREFIXES["mms-object"]}Role.$id"

/**
 * Generates the pattern that looks up the class of a candidate policy scope IRI.
 *
 * Orgs, repos, and collections are typed in `m-graph:Cluster`, but repo-nested resources
 * (branches, locks, scratches, diffs, commits, artifacts) are typed only in their repo's
 * `Metadata`/`Artifacts` graph, and access-control resources are typed in the AccessControl
 * graphs. The lookup must accept typing from the appropriate graph, otherwise policies scoped
 * directly to those resources (including every `autoPolicy(...)` grant) can never authorize.
 *
 * The repo graph is matched by IRI suffix via a graph variable instead of the `mor-graph:`
 * prefix because this BGP is also emitted for requests that carry no repo context (e.g. policy
 * writes, which pass explicit [scopeUris]). The scope VALUES are repeated inside that union
 * branch so the subject is bound during bottom-up evaluation — without it, the branch would
 * scan every rdf:type triple across all graphs before joining.
 */
private fun scopeTypeLookup(scope: Scope, scopeValuesClause: String): String {
    val clusterLookup = """
        graph m-graph:Cluster {
            ?__mms_scope rdf:type ?__mms_scopeType .
        }
    """.trimIndent()

    return when(scope) {
        Scope.BRANCH, Scope.LOCK, Scope.SCRATCH, Scope.DIFF, Scope.COMMIT, Scope.ARTIFACT -> {
            val repoGraphSuffix = if(scope == Scope.ARTIFACT) "/graphs/Artifacts" else "/graphs/Metadata"
            """
            {
                $clusterLookup
            } union {
                # repo-nested resources are typed in a repo graph rather than the cluster graph;
                # re-bind the candidate scopes so this branch evaluates with a bounded subject
                values ?__mms_scope {
                    $scopeValuesClause
                }
                graph ?__mms_scopeTypeGraph {
                    ?__mms_scope rdf:type ?__mms_scopeType .
                }
                filter(strends(str(?__mms_scopeTypeGraph), "$repoGraphSuffix"))
            }
            """
        }
        Scope.GROUP, Scope.USER -> """
            {
                $clusterLookup
            } union {
                # agents are typed in the access control graph rather than the cluster graph
                graph m-graph:AccessControl.Agents {
                    ?__mms_scope rdf:type ?__mms_scopeType .
                }
            }
        """
        Scope.POLICY -> """
            {
                $clusterLookup
            } union {
                # policies are typed in the access control graph rather than the cluster graph
                graph m-graph:AccessControl.Policies {
                    ?__mms_scope rdf:type ?__mms_scopeType .
                }
            }
        """
        else -> clusterLookup
    }
}

@JvmOverloads
fun permittedActionSparqlBgp(
    permission: Permission,
    scope: Scope,
    find: Regex?=null,
    replace: String?=null,
    scopeUris: List<String>?=null,
    scopeJoinVars: List<String>?=null,
): String {
    // when explicit scope URIs are supplied, emit them as full IRIs; otherwise fall back to the
    // prefix-based scope chain derived from the active request context (`scope.values()`).
    val scopeValuesClause = if(scopeUris != null) {
        scopeUris.joinToString(" ") { "<$it>" }
    } else {
        scope.values().joinToString(" ") { it.run {
            if(find != null && replace != null) this.replace(find, replace) else this
        } }
    }

    // when scopeJoinVars is provided, filter the policy-bound ?__mms_scope so that it can match
    // either the fixed scope values OR the resource variable(s) from the main query pattern.
    // This must not be a UNION of `bind(?var as ?__mms_scope)` branches: under SPARQL's bottom-up
    // evaluation the joined variable is unbound inside an independent union branch. Jena happens
    // to substitute it, but spec-conformant stores (e.g., Virtuoso) do not and return no results.
    val scopePattern = if(scopeJoinVars != null) {
        val scopeInClause = scopeValuesClause.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(", ")
        val joinDisjuncts = scopeJoinVars.joinToString(" || ") { "sameTerm(?__mms_scope, ?$it)" }
        if(scopeInClause.isEmpty()) "filter($joinDisjuncts)"
        else "filter(?__mms_scope in ($scopeInClause) || $joinDisjuncts)"
    } else {
        """values ?__mms_scope {
            $scopeValuesClause
        }"""
    }

    return """
        # some policy exists
        graph m-graph:AccessControl.Policies {
            ?__mms_policy a mms:Policy ;
                mms:scope ?__mms_scope ;
                mms:role ?__mms_role ;
                ?__mms_policy_p ?__mms_policy_o .
        }

        # deduce `?__mms_authMethod`
        {   
            # the policy applies to this user within an appropriate scope
            graph m-graph:AccessControl.Policies {
                # policy about user
                ?__mms_policy mms:subject mu: .
            }
    
            # indicate method for authentication was against user
            bind("user" as ?__mms_authMethod)
        } union {
            # user belongs to some group
            graph m-graph:AccessControl.Agents {
                ?__mms_group a mms:Group ;
                    mms:id ?__mms_groupId .
                values ?__mms_groupId {
                    # @values groupId                
                }
            }

        
            # a policy exists that applies to this group within an appropriate scope
            graph m-graph:AccessControl.Policies {
                # or policy about group user belongs to
                ?__mms_policy mms:subject ?__mms_group .
            }
    
            # indicate method for authentication was against group
            bind("group" as ?__mms_authMethod)
        }


        # intersect scopes relevant to context
        $scopePattern

        # lookup scope's class
        ${scopeTypeLookup(scope, scopeValuesClause)}

        # scope classes whose scope covers `mms:${scope.type}` and roles granting the permission; both are
        # resolved from the static access control definitions (see AccessControlDefinitions.kt) because the
        # equivalent property paths are costly and not evaluated correctly by every quad-store
        # @iris ?__mms_scopeType in scopeTypes:${scope.type}
        # @iris ?__mms_role in rolesGranting:${permission.id}
    """
}

fun generateReadContextBgp(permission: Permission, id: String?=null): String {
    return """ 
        # context conveys metadata such as etag and how access control was applied 
        <${MMS_URNS.SUBJECT.context}${id?.let { ":$it" }?: ""}> a mms:Context ;
            mms:etag ?__mms_etag, ?elementEtag ;
            mms:appliedPolicy ?__mms_policy ;
            mms:permit mms-object:Permission.${permission.id} .
        # details the policy that was applied
        #?__mms_policy ?__mms_policy_p ?__mms_policy_o .
    """
}

package org.openmbee

import io.ktor.application.*
import java.util.*

const val SPARQL_BGP_USER_EXISTS = """
    # user must exist
    graph m-graph:AccessControl.Agents {
        mu: a mms:User .
    }
"""

enum class Permission(val id: String) {
    CREATE_ORG("CreateOrg"),
    READ_ORG("ReadOrg"),
    UPDATE_ORG("UpdateOrg"),
    DELETE_ORG("DeleteOrg"),

    CREATE_REPO("CreateRepo"),
    READ_REPO("ReadRepo"),
    UPDATE_REPO("UpdateRepo"),
    DELETE_REPO("DeleteRepo"),

    CREATE_BRANCH("CreateBranch"),
    READ_BRANCH("ReadBranch"),
    UPDATE_BRANCH("UpdateBranch"),
    DELETE_BRANCH("DeleteBranch"),
}

enum class Scope(val type: String, val id: String) {
    CLUSTER("Cluster", "m"),
    ORG("Org", "mo"),
    // COLLECTION("Repo", "m", "mo", "moc"),
    REPO("Repo", "mor"),
    BRANCH("Branch", "morb"),
    // LOCK("Lock",
    //     "m", "mo", "mor", "mor-lock"),
}

fun Scope.values() = sequence<String> {
    for(i in 1..id.length) {
        yield(id.substring(0, i))
    }
}

enum class Role(val id: String) {
    ADMIN_ORG("AdminOrg"),
    ADMIN_REPO("AdminRepo"),
    ADMIN_METADATA("AdminMetadata"),
    ADMIN_MODEL("AdminModel"),
}

val ApplicationCall.mmsUserId: String
    get() = this.request.headers["mms5-user"]?: ""

fun permittedActionSparqlBgp(permission: Permission, scope: Scope): String {
    return """
        # user exists and may belong to some group
        graph m-graph:AccessControl.Agents {
            mu: a mms:User .
            
            optional {
                mu: mms:group* ?group .
                ?group a mms:Group .
            }
        }
        
        # a policy exists that applies to this user/group within an appropriate scope
        graph m-graph:AccessControl.Policies {
            ?policy a mms:Policy ;
                mms:scope ?scope ;
                mms:role ?role ;
                .
            
            {
                # policy about user
                ?policy mms:subject mu: .
            } union {
                # or policy about group user belongs to
                ?policy mms:subject ?group .
            }
            
            # intersect scopes relevant to context
            values ?scope {
                ${scope.values().joinToString(" ") { "$it:" } }
            }
        }
        
        # lookup scope's class
        graph m-graph:Cluster {
            ?scope rdf:type ?scopeType .
        }
        
        # lookup scope class, role, and permissions
        graph m-graph:AccessControl.Definitions {
            ?scopeType rdfs:subClassOf*/mms:implies*/^rdfs:subClassOf* mms:${scope.type} .

            ?role a mms:Role ;
                mms:permits ?directRolePermissions ;
                .
            
            ?directRolePermissions a mms:Permission ;
                mms:implies* mms-object:Permission.${permission.id} ;
                .
        }
    """.trimIndent()
}

fun autoPolicySparqlBgp(builder: InsertBuilder, prefixes: PrefixMapBuilder, scope: Scope, roles: List<Role>): InsertBuilder {
    return builder.run {
        graph("m-graph:AccessControl.Policies") {
            raw(
                """
                m-policy:Auto${scope.type}Owner.${UUID.randomUUID()} a mms:Policy ;
                    mms:subject mu: ;
                    mms:scope ${scope.id}: ;
                    mms:role ${roles.joinToString(",") { "mms-object:Role.${it.id}" }}  ;
                    .
            """
            )
        }
    }
}

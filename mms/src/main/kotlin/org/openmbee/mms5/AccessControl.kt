package org.openmbee.mms5

import io.ktor.application.*

enum class Crud(val id: String) {
    CREATE("Create"),
    READ("Read"),
    UPDATE("Update"),
    DELETE("Delete"),
}

enum class Scope(val type: String, val id: String) {
    CLUSTER("Cluster", "m"),
    ORG("Org", "mo"),
    COLLECTION("Collection", "moc"),
    REPO("Repo", "mor"),
    BRANCH("Branch", "morb"),
    LOCK("Lock", "morcl"),
    DIFF("Diff", "mord"),

    ACCESS_CONTROL("AccessControl", "ma"),
    GROUP("Group", "mag")
}

fun Scope.values() = sequence<String> {
    for(i in 1..id.length) {
        yield(id.substring(0, i))
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

    CREATE_DIFF(Crud.CREATE, Scope.DIFF),
    READ_DIFF(Crud.READ, Scope.DIFF),
    UPDATE_DIFF(Crud.UPDATE, Scope.DIFF),
    DELETE_DIFF(Crud.DELETE, Scope.DIFF),

    CREATE_GROUP(Crud.CREATE, Scope.GROUP)
}


enum class Role(val id: String) {
    ADMIN_ORG("AdminOrg"),
    ADMIN_REPO("AdminRepo"),
    ADMIN_METADATA("AdminMetadata"),
    ADMIN_MODEL("AdminModel"),
    ADMIN_LOCK("AdminLock"),
    ADMIN_BRANCH("AdminBranch"),
    ADMIN_DIFF("AdminDiff"),
    ADMIN_GROUP("AdminGroup"),
}

fun permittedActionSparqlBgp(permission: Permission, scope: Scope): String {
    return """
        # user exists and may belong to some group
        graph m-graph:AccessControl.Agents {
            {
                mu: a mms:User .
                
                optional {
                    mu: mms:group* ?group .
                    ?group a mms:Group .
                }
            } union {
                ?group a mms:LdapGroup ;
                    mms:id ?__mms_ldapGroupDn .
                    
                values ?__mms_ldapGroupDn {
                    # @sparql://mms5.openmbee.org/replace/?__mms_ldapGroupDn
                }
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

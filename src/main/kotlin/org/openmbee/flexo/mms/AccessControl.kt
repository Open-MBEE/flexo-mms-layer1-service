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
    DIFF("Diff", "mord"),

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
    ADMIN_DIFF("AdminDiff"),
    ADMIN_GROUP("AdminGroup"),
    ADMIN_POLICY("AdminPolicy"),
}

@JvmOverloads
fun permittedActionSparqlBgp(permission: Permission, scope: Scope, find: Regex?=null, replace: String?=null): String {
    return """
        # some policy exists
        graph m-graph:AccessControl.Policies {
            ?__mms_policy a mms:Policy ;
                mms:scope ?__mms_scope ;
                mms:role ?__mms_role ;
                ?__mms_policy_p ?__mms_policy_o ;
                .
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
                    mms:id ?__mms_groupId ;
                    .
        
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
        values ?__mms_scope {
            ${scope.values().joinToString(" ") { it.run {
                if(find != null && replace != null) this.replace(find, replace) else this
            } } }
        }
    
        # lookup scope's class
        graph m-graph:Cluster {
            ?__mms_scope rdf:type ?__mms_scopeType .
        }
        
        # lookup scope class, role, and permissions
        graph m-graph:AccessControl.Definitions {
            ?__mms_scopeType rdfs:subClassOf*/mms:implies*/^rdfs:subClassOf* mms:${scope.type} .

            ?__mms_role a mms:Role ;
                mms:permits ?__mms_directRolePermissions ;
                .
            
            ?__mms_directRolePermissions a mms:Permission ;
                mms:implies* mms-object:Permission.${permission.id} ;
                .
        }
    """
}

fun generateReadContextBgp(permission: Permission, id: String?=null): String {
    return """ 
        # context conveys metadata such as etag and how access control was applied 
        <${MMS_URNS.SUBJECT.context}${id?.let { ":$it" }?: ""}> a mms:Context ;
            mms:etag ?__mms_etag, ?elementEtag ;
            mms:appliedPolicy ?__mms_policy ;
            mms:permit mms-object:Permission.${permission.id} ;
            .

        # details the policy that was applied
        ?__mms_policy ?__mms_policy_p ?__mms_policy_o .
    """
}

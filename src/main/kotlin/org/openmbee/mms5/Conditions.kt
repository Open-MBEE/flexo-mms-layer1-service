package org.openmbee.mms5

import io.ktor.http.*
import org.apache.jena.rdf.model.impl.PropertyImpl
import org.openmbee.mms5.plugins.UserDetailsPrincipal

val GLOBAL_CRUD_CONDITIONS = conditions {
    inspect("agentExists") {
        handler = { mms -> "User <${mms.prefixes["mu"]}> does not exist or does not belong to any authorized groups." to HttpStatusCode.Forbidden }

        """
            {
                # user exists
                graph m-graph:AccessControl.Agents {
                    mu: a mms:User .
                }
        
                bind("user" as ?__mms_authMethod)
            } union {
                # user belongs to some group
                graph m-graph:AccessControl.Agents {
                    ?group a mms:Group ;
                        mms:id ?__mms_groupId .
            
                    values ?__mms_groupId {
                        # @values groupId                
                    }
                }

                bind("group" as ?__mms_authMethod)
            }
        """
    }
}

val ORG_CRUD_CONDITIONS = GLOBAL_CRUD_CONDITIONS.append {
    orgExists()
}

val REPO_CRUD_CONDITIONS = ORG_CRUD_CONDITIONS.append {
    require("repoExists") {
        handler = { mms -> "Repo <${mms.prefixes["mor"]}> does not exist." to HttpStatusCode.NotFound }

        """
            # repo must exist
            graph m-graph:Cluster {
                mor: a mms:Repo .
            }
        """
    }
}

val BRANCH_COMMIT_CONDITIONS = REPO_CRUD_CONDITIONS.append {
    permit(Permission.UPDATE_BRANCH, Scope.BRANCH)

    require("stagingExists") {
        handler = { mms -> "The destination branch <${mms.prefixes["morb"]}> is corrupt. No staging snapshot found." to HttpStatusCode.InternalServerError }

        """
            graph mor-graph:Metadata {
                # select the latest commit from the current named ref
                morb: mms:commit ?baseCommit ;
                    # and its etag value
                    mms:etag ?branchEtag .
            
                # and its staging snapshot
                morb: mms:snapshot ?staging .
                ?staging a mms:Staging ;
                    mms:graph ?stagingGraph .
            }
        """
    }

//
//                optional {
//                    # optionally, its model snapshot
//                    morc: ^mms:commit/mms:snapshot ?model .
//                    ?model a mms:Model ;
//                        mms:graph ?modelGraph .
//                }
}

val SNAPSHOT_QUERY_CONDITIONS = REPO_CRUD_CONDITIONS.append {
    require("queryableSnapshotExists") {
        handler = { mms -> "The target model is corrupt. No queryable snapshots found." to HttpStatusCode.InternalServerError }

        """
            graph mor-graph:Metadata {
                ?__mms_ref
                    # select the latest commit from the current named ref
                    mms:commit ?__mms_baseCommit ;

                    # and its etag value
                    mms:etag ?__mms_etag ;

                    # and a queryable snapshot
                    mms:snapshot/mms:graph ?__mms_queryGraph .
            }
        """
    }
}

val REPO_QUERY_CONDITIONS = SNAPSHOT_QUERY_CONDITIONS.append {
    permit(Permission.READ_REPO, Scope.REPO)
}

val BRANCH_QUERY_CONDITIONS = SNAPSHOT_QUERY_CONDITIONS.append {
    permit(Permission.READ_BRANCH, Scope.BRANCH)
}

val LOCK_QUERY_CONDITIONS = SNAPSHOT_QUERY_CONDITIONS.append {
    permit(Permission.READ_LOCK, Scope.LOCK)
}

val DIFF_QUERY_CONDITIONS = SNAPSHOT_QUERY_CONDITIONS.append {
    permit(Permission.READ_DIFF, Scope.DIFF)
}

val COMMIT_CRUD_CONDITIONS = REPO_CRUD_CONDITIONS.append {
    require("commitExists") {
        handler = { mms -> "Commit <${mms.prefixes["morc"]}> does not exist." to HttpStatusCode.NotFound }

        """
            # commit must exist
            graph mor-graph:Metadata {
                morc: a mms:Commit .
            }
        """
    }
}

val LOCK_CRUD_CONDITIONS = REPO_CRUD_CONDITIONS.append {
    require("lockExists") {
        handler = { mms -> "Lock <${mms.prefixes["morl"]}> does not exist." to HttpStatusCode.NotFound }

        """
            # lock must exist
            graph mor-graph:Metadata {
                morl: a mms:Lock .
            }
        """
    }
}

enum class ConditionType {
    INSPECT,
    REQUIRE,
}

class Condition(val type: ConditionType, val key: String) {
    var pattern: String = ""
    var handler: (mms: MmsL1Context) -> Pair<String, HttpStatusCode> = {
        "`$key` condition failed" to HttpStatusCode.BadRequest
    }
}


class ConditionsBuilder(val conditions: MutableList<Condition> = arrayListOf()) {
    /**
     * Adds a requirement to the query conditions that the current user has the given permission within the given scope.
     */
    fun permit(permission: Permission, scope: Scope): ConditionsBuilder {
        return require(permission.id) {
            handler = { mms -> "User <${mms.prefixes["mu"]}> is not permitted to ${permission.id}. Observed LDAP groups include: ${mms.groups.joinToString(", ")}" to HttpStatusCode.Forbidden }

            permittedActionSparqlBgp(permission, scope)
        }
    }

    fun orgExists() {
        require("orgExists") {
            handler = { mms -> "Org <${mms.prefixes["mo"]}> does not exist." to HttpStatusCode.NotFound }

            """
                # org must exist
                graph m-graph:Cluster {
                    mo: a mms:Org .
                }
            """
        }
    }

    /**
     * Adds a pattern to the query conditions that is only evaluated upon inspection.
     */
    fun inspect(key: String, setup: Condition.()->String): ConditionsBuilder {
        conditions.add(Condition(ConditionType.INSPECT, key).apply {
            pattern = setup()
        })

        return this
    }

    /**
     * Adds a requirement to the query conditions.
     */
    fun require(key: String, setup: Condition.()->String): ConditionsBuilder {
        conditions.add(Condition(ConditionType.REQUIRE, key).apply {
            pattern = setup()
        })

        return this
    }
}

class ConditionsGroup(var conditions: List<Condition>) {
    fun required(): List<Condition> {
        return conditions.filter { ConditionType.REQUIRE == it.type }
    }

    fun requiredPatterns(): Array<String> {
        return required().map { it.pattern }.toTypedArray()
    }

    fun inspect(): List<Condition> {
        return conditions.filter { ConditionType.INSPECT == it.type }
    }

    fun inspectPatterns(varName: String="__mms_inspect_pass"): List<String> {
        return conditions.map {
            """
                {
                    ${it.pattern}
                    
                    bind("${it.key}" as ?$varName)
                }
            """
        }
    }

    fun unionInspectPatterns(): String {
        return inspectPatterns().joinToString(" union ")
    }

    fun append(setup: ConditionsBuilder.()->Unit): ConditionsGroup {
        return ConditionsGroup(ConditionsBuilder(conditions.toMutableList()).apply{setup()}.conditions)
    }

    fun handle(model: KModel, mms: MmsL1Context): Nothing {
        // inspect node
        val inspectNode = model.createResource("urn:mms:inspect")
        val passes = inspectNode.listProperties(PropertyImpl("urn:mms:pass")).toList()
            .map { it.`object`.asLiteral().string }.toHashSet()

        val failedConditions = mutableListOf<Pair<String, HttpStatusCode>>()

        // each conditions
        for(condition in conditions) {
            // inspection key is missing from set of passes
            if(!passes.contains(condition.key)) {
                val (msg, code) = condition.handler(mms)

                // add to possible reasons
                failedConditions.add(msg to code)

                // forbidden; fail right away
                if(code == HttpStatusCode.Forbidden) {
                    throw Http403Exception(mms, msg)
                }
            }
        }

        if(failedConditions.isNotEmpty()) {
            if(failedConditions.size == 1) {
                val (msg, code) = failedConditions[0]

                throw HttpException(msg, code)
            }
            else {
                throw RequirementsNotMetException(failedConditions.map { it.first})
            }
        }

        throw ServerBugException("Unable to verify transaction from CONSTRUCT response; pattern failed to match anything")
    }
}

fun conditions(setup: ConditionsBuilder.()->Unit): ConditionsGroup {
    return ConditionsGroup(ConditionsBuilder().apply{setup()}.conditions)
}


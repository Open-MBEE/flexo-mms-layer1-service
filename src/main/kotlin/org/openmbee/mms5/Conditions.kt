package org.openmbee.mms5

import org.apache.jena.rdf.model.impl.PropertyImpl
import org.openmbee.mms5.plugins.UserDetailsPrincipal

val GLOBAL_CRUD_CONDITIONS = conditions {
    inspect("agentExists") {
        handler = { mms -> "User <${mms.prefixes["mu"]}> does not exist or does not belong to any authorized groups." }

        """
            graph m-graph:AccessControl.Agents {
                {
                    mu: a mms:User .
                } union {
                    ?group a mms:Group ;
                        mms:id ?__mms_groupId .

                    values ?__mms_groupId {
                        # @values groupId
                    }
                }
            }
        """
    }
}

val ORG_CRUD_CONDITIONS = GLOBAL_CRUD_CONDITIONS.append {
    orgExists()
}

val REPO_CRUD_CONDITIONS = ORG_CRUD_CONDITIONS.append {
    require("repoExists") {
        handler = { mms -> "Repo <${mms.prefixes["mor"]}> does not exist." }

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
        handler = { mms -> "The destination branch <${mms.prefixes["morb"]}> is corrupt. No staging snapshot found." }

        """
            graph mor-graph:Metadata {
                # select the latest commit from the current named ref
                morb: mms:commit ?baseCommit ;
                    # and its etag value
                    mms:etag ?__mms_etag .
            
                # and its staging snapshot
                morb: mms:snapshot ?staging .
                ?staging a mms:Staging ;
                    mms:graph ?stagingGraph ;
                    .
            
                optional {
                    # optionally, its model snapshot
                    morb: mms:snapshot ?model .
                    ?model a mms:Model ;
                        mms:graph ?modelGraph ;
                        .
                }
            }
        """
    }
}

val REPO_QUERY_CONDITIONS = REPO_CRUD_CONDITIONS.append {
    permit(Permission.READ_REPO, Scope.REPO)

    require("queryableSnapshotExists") {
        handler = { mms -> "The target model is corrupt. No queryable snapshots found." }

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

val BRANCH_QUERY_CONDITIONS = REPO_QUERY_CONDITIONS.append {
    permit(Permission.READ_BRANCH, Scope.BRANCH)
}

val LOCK_QUERY_CONDITIONS = REPO_QUERY_CONDITIONS.append {
    permit(Permission.READ_LOCK, Scope.LOCK)
}

val DIFF_QUERY_CONDITIONS = REPO_QUERY_CONDITIONS.append {
    permit(Permission.READ_DIFF, Scope.DIFF)
}

val COMMIT_CRUD_CONDITIONS = REPO_CRUD_CONDITIONS.append {
    require("commitExists") {
        handler = { mms -> "Commit <${mms.prefixes["morc"]}> does not exist." }

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
        handler = { mms -> "Lock <${mms.prefixes["morl"]}> does not exist." }

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
    var handler: (mms: MmsL1Context) -> String = {
        "`$key` condition failed"
    }
}


class ConditionsBuilder(val conditions: MutableList<Condition> = arrayListOf()) {
    /**
     * Adds a requirement to the query conditions that the current user has the given permission within the given scope.
     */
    fun permit(permission: Permission, scope: Scope): ConditionsBuilder {
        return require(permission.id) {
            handler = { mms -> "User <${mms.prefixes["mu"]}> is not permitted to ${permission.id}. Observed LDAP groups include: ${mms.groups.joinToString(", ")}" }

            permittedActionSparqlBgp(permission, scope)
        }
    }

    fun orgExists() {
        require("orgExists") {
            handler = { mms -> "Org <${mms.prefixes["mo"]}> does not exist." }

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

        val failedConditions = mutableListOf<String>()

        // each conditions
        for(condition in conditions) {
            // inspection key is missing from set of passes
            if(!passes.contains(condition.key)) {
                // add to possible reasons
                failedConditions.add(condition.handler(mms))
            }
        }

        if(failedConditions.isNotEmpty()) {
            throw RequirementsNotMetException(failedConditions)
        }

        throw ServerBugException("Unable to verify transaction from CONSTRUCT response; pattern failed to match anything")
    }
}

fun conditions(setup: ConditionsBuilder.()->Unit): ConditionsGroup {
    return ConditionsGroup(ConditionsBuilder().apply{setup()}.conditions)
}


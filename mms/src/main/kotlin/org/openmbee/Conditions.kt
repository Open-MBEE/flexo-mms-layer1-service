package org.openmbee

import org.apache.jena.rdf.model.impl.PropertyImpl

val GLOBAL_CRUD_CONDITIONS = conditions {
    inspect("userExists") {
        handler = { prefixes -> "User <${prefixes["mu"]}> does not exist." }

        """
            graph m-graph:AccessControl.Agents {
                mu: a mms:User .
            }
        """
    }
}

val ORG_CRUD_CONDITIONS = GLOBAL_CRUD_CONDITIONS.append {
    orgExists()
}

val REPO_CRUD_CONDITIONS = ORG_CRUD_CONDITIONS.append {
    require("repoExists") {
        handler = { prefixes -> "Repo <${prefixes["mor"]}> does not exist." }

        """
            # repo must exist
            graph m-graph:Cluster {
                mor: a mms:Repo .
            }
        """
    }
}

val COMMIT_CRUD_CONDITIONS = REPO_CRUD_CONDITIONS.append {
    require("commitExists") {
        handler = { prefixes -> "Commit <${prefixes["morc"]}> does not exist." }

        """
            # commit must exist
            graph mor-graph:Metadata {
                morc: a mms:Commit .
            }
        """
    }
}

val LOCK_CRUD_CONDITIONS = COMMIT_CRUD_CONDITIONS.append {
    require("lockExists") {
        handler = { prefixes -> "Lock <${prefixes["morcl"]}> does not exist." }

        """
            # lock must exist
            graph mor-graph:Metadata {
                morcl: a mms:Lock .
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
    var handler: (prefixes: PrefixMapBuilder) -> String = {
        "`$key` condition failed"
    }
}


class ConditionsBuilder(val conditions: MutableList<Condition> = arrayListOf()) {
    fun permit(permission: Permission, scope: Scope): ConditionsBuilder {
        return require(permission.id) {
            handler = { prefixes -> "User <${prefixes["mu"]}> is not permitted to ${permission.id}." }

            permittedActionSparqlBgp(permission, scope)
        }
    }

    fun orgExists() {
        require("orgExists") {
            handler = { prefixes -> "Org <${prefixes["mo"]}> does not exist." }

            """
                # org must exist
                graph m-graph:Cluster {
                    mo: a mms:Org .
                }
            """
        }
    }

    fun inspect(key: String, setup: Condition.()->String): ConditionsBuilder {
        conditions.add(Condition(ConditionType.INSPECT, key).apply {
            pattern = setup()
        })

        return this
    }

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

    fun inspectPatterns(varName: String="inspect"): List<String> {
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

    fun handle(model: KModel): Nothing {
        // inspect node
        val inspectNode = model.createResource("mms://inspect")
        val passes = inspectNode.listProperties(PropertyImpl("mms://pass")).toList()
            .map { it.`object`.asLiteral().string }.toHashSet()

        // each conditions
        for(condition in conditions) {
            // inspection key is missing from set of passes
            if(!passes.contains(condition.key)) {
                throw RequirementNotMetException(condition.handler(model.prefixes))
            }
        }

        throw Exception("Unable to verify transaction from CONSTRUCT response")
    }
}

fun conditions(setup: ConditionsBuilder.()->Unit): ConditionsGroup {
    return ConditionsGroup(ConditionsBuilder().apply{setup()}.conditions)
}


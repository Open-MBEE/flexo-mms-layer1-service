package org.openmbee

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
        return inspect().map {
            """
                {
                    ${it.pattern}
                    
                    bind("${it.key}" as ?$varName)
                }
            """.trimIndent()
        }
    }

    fun unionInspectPatterns(): String {
        return inspectPatterns().joinToString(" union ")
    }

    fun append(setup: ConditionsBuilder.()->Unit): ConditionsGroup {
        return ConditionsGroup(ConditionsBuilder(conditions.toMutableList()).apply{setup()}.conditions)
    }
}

fun conditions(setup: ConditionsBuilder.()->Unit): ConditionsGroup {
    return ConditionsGroup(ConditionsBuilder().apply{setup()}.conditions)
}


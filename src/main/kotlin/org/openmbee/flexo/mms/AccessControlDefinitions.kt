package org.openmbee.flexo.mms

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicReference

private const val RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"
private const val RDFS_SUBCLASS_OF = "http://www.w3.org/2000/01/rdf-schema#subClassOf"

// how long a loaded copy of the definitions graph is reused before it is re-read
private const val DEFINITIONS_TTL_MILLIS = 5 * 60 * 1000L

/**
 * In-memory copy of the static `m-graph:AccessControl.Definitions` graph (scope classes, roles and
 * permissions), used to resolve the transitive parts of access control checks in Kotlin rather than
 * with SPARQL property paths.
 *
 * Evaluating the property paths `?scopeType rdfs:subClassOf* / mms:implies* / ^rdfs:subClassOf* mms:{Scope}` and
 * `?permission mms:implies* mms-object:Permission.{Id}` inside every authorized query is both costly
 * and unportable: some stores (e.g., Virtuoso) cannot evaluate chained `*` paths inside a GRAPH block
 * and silently return no results. The definitions graph is generated once at deployment and never
 * written by the service, so the matching scope types and roles are computed here and inlined into
 * queries as FILTERs via the `# @iris ...` directives emitted by [permittedActionSparqlBgp].
 */
class AccessControlDefinitions(
    private val subClassOf: Map<String, Set<String>>,
    private val implies: Map<String, Set<String>>,
    private val types: Map<String, Set<String>>,
    private val permits: Map<String, Set<String>>,
) {
    private val mms = SPARQL_PREFIXES["mms"]!!
    private val mmsObject = SPARQL_PREFIXES["mms-object"]!!

    private val nodes: Set<String> = buildSet {
        for(rel in listOf(subClassOf, implies)) {
            addAll(rel.keys)
            rel.values.forEach { addAll(it) }
        }
    }

    private val scopeTypesCache = mutableMapOf<String, List<String>>()
    private val rolesCache = mutableMapOf<String, List<String>>()

    val isEmpty: Boolean
        get() = nodes.isEmpty() && permits.isEmpty()

    // reflexive transitive closure of `rel` starting from `start` (i.e., SPARQL's `rel*`)
    private fun closure(rel: Map<String, Set<String>>, start: String): Set<String> {
        val seen = mutableSetOf(start)
        val stack = ArrayDeque(listOf(start))
        while(stack.isNotEmpty()) {
            for(next in rel[stack.removeLast()].orEmpty()) {
                if(seen.add(next)) stack.addLast(next)
            }
        }
        return seen
    }

    /**
     * IRIs of every scope class `?t` satisfying `?t rdfs:subClassOf* / mms:implies* / ^rdfs:subClassOf* mms:{scopeType}`.
     * A class that appears in no subClassOf/implies triple can only satisfy the path through zero-length steps,
     * i.e., when it is the target class itself, which is always included.
     */
    fun scopeTypesFor(scopeType: String): List<String> = synchronized(scopeTypesCache) {
        scopeTypesCache.getOrPut(scopeType) {
            val target = "$mms$scopeType"
            val targetSupers = closure(subClassOf, target)
            (nodes + target).filter { candidate ->
                closure(subClassOf, candidate).any { via ->
                    closure(implies, via).any { it in targetSupers }
                }
            }.sorted()
        }
    }

    /**
     * IRIs of every `?role a mms:Role` that `mms:permits` some `mms:Permission` which `mms:implies*` the given permission.
     */
    fun rolesGranting(permissionId: String): List<String> = synchronized(rolesCache) {
        rolesCache.getOrPut(permissionId) {
            val target = "${mmsObject}Permission.$permissionId"
            permits.filter { (role, permissions) ->
                "${mms}Role" in types[role].orEmpty() && permissions.any { permission ->
                    "${mms}Permission" in types[permission].orEmpty() && target in closure(implies, permission)
                }
            }.keys.sorted()
        }
    }

    companion object {
        private data class Loaded(val definitions: AccessControlDefinitions, val at: Long)

        private val cached = AtomicReference<Loaded?>(null)

        /**
         * Returns the definitions, reading the graph from the quad-store when there is no fresh copy.
         * An empty graph (e.g., the store has not been initialized yet) is never cached, so it is re-read
         * on the next request; until then every access control check denies.
         */
        suspend fun get(context: AnyLayer1Context): AccessControlDefinitions {
            cached.get()?.let { if(System.currentTimeMillis() - it.at < DEFINITIONS_TTL_MILLIS) return it.definitions }

            val definitions = load(context)
            if(!definitions.isEmpty) cached.set(Loaded(definitions, System.currentTimeMillis()))
            return definitions
        }

        private suspend fun load(context: AnyLayer1Context): AccessControlDefinitions {
            val responseText = context.executeSparqlSelectOrAsk("""
                select ?s ?p ?o {
                    graph m-graph:AccessControl.Definitions {
                        ?s ?p ?o .
                        values ?p {
                            rdf:type
                            rdfs:subClassOf
                            mms:implies
                            mms:permits
                        }
                    }
                }
            """)

            val subClassOf = mutableMapOf<String, MutableSet<String>>()
            val implies = mutableMapOf<String, MutableSet<String>>()
            val types = mutableMapOf<String, MutableSet<String>>()
            val permits = mutableMapOf<String, MutableSet<String>>()
            val mms = SPARQL_PREFIXES["mms"]!!

            for(binding in parseSparqlResultsJsonSelect(responseText)) {
                val o = binding["o"]!!.jsonObject
                // only IRIs participate in the class and permission hierarchies
                if(o["type"]?.jsonPrimitive?.content != "uri") continue

                val s = binding["s"]!!.jsonObject["value"]!!.jsonPrimitive.content
                val target = when(binding["p"]!!.jsonObject["value"]!!.jsonPrimitive.content) {
                    RDF_TYPE -> types
                    RDFS_SUBCLASS_OF -> subClassOf
                    "${mms}implies" -> implies
                    "${mms}permits" -> permits
                    else -> continue
                }
                target.getOrPut(s) { mutableSetOf() }.add(o["value"]!!.jsonPrimitive.content)
            }

            return AccessControlDefinitions(subClassOf, implies, types, permits)
        }
    }
}

private val IRIS_DIRECTIVE_REGEX = """\n\s*#+\s*@iris\s+\?(\w+)\s+in\s+(scopeTypes|rolesGranting):(\w+)[ \t]*(?=\n)""".toRegex()

/**
 * Replaces the `# @iris ?{var} in scopeTypes:{ScopeType}` and `# @iris ?{var} in rolesGranting:{PermissionId}`
 * directives emitted by [permittedActionSparqlBgp] with a FILTER restricting the variable to the matching IRIs
 * computed from the access control definitions. A FILTER is used rather than VALUES since stores can push it
 * down to where the variable is bound (a VALUES table is joined late by Jena, for example); an empty set of
 * IRIs yields `filter(false)` so that the check denies.
 */
suspend fun AnyLayer1Context.replaceIrisDirectives(sparql: String): String {
    if(!IRIS_DIRECTIVE_REGEX.containsMatchIn(sparql)) return sparql

    val definitions = AccessControlDefinitions.get(this)
    return IRIS_DIRECTIVE_REGEX.replace(sparql) { match ->
        val (variable, kind, id) = match.destructured
        val iris = when(kind) {
            "scopeTypes" -> definitions.scopeTypesFor(id)
            else -> definitions.rolesGranting(id)
        }
        val filter = if(iris.isEmpty()) "filter(false)"
            else "filter(?$variable in (${iris.joinToString(", ") { "<$it>" }}))"
        "\n$filter"
    }
}

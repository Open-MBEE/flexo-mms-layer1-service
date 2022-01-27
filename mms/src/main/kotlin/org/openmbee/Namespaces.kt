package org.openmbee

import org.apache.jena.datatypes.BaseDatatype
import org.apache.jena.rdf.model.impl.PropertyImpl
import org.apache.jena.rdf.model.impl.ResourceImpl
import org.apache.jena.shared.PrefixMapping

class PrefixMapBuilder(other: PrefixMapBuilder?=null, setup: (PrefixMapBuilder.() -> PrefixMapBuilder)?=null) {
    var map = HashMap<String, String>()

    init {
        if(null != other) map.putAll(other.map)
        if(null != setup) setup(this)
    }

    fun add(vararg adds: Pair<String, String>): PrefixMapBuilder {
        map.putAll(adds)
        return this
    }

    operator fun get(prefix: String): String? {
        return map[prefix]
    }

    override fun toString(): String {
        return map.entries.fold("") {
            out, (key, value) -> out + "prefix $key: <$value>\n"
        }
    }

    fun toPrefixMappings(): PrefixMapping {
        return PrefixMapping.Factory.create().run {
            setNsPrefixes(map)
        }
    }
}

val SPARQL_PREFIXES = PrefixMapBuilder() {
    add(
        "rdf" to "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
        "rdfs" to "http://www.w3.org/2000/01/rdf-schema#",
        "owl" to "http://www.w3.org/2002/07/owl#",
        "xsd" to "http://www.w3.org/2001/XMLSchema#",
        "dct" to "http://purl.org/dc/terms/",
    )

    with("https://mms.openmbee.org/rdf") {
        add(
            "mms" to "$this/ontology/",
            "mms-txn" to "$this/ontology/txn.",
            "mms-object" to "$this/objects/",
            "mms-datatype" to "$this/datatypes/",
        )
    }

    with(ROOT_CONTEXT) {
        add(
            "m" to "$this/",
            "m-object" to "$this/objects/",
            "m-graph" to "$this/graphs/",
            "m-org" to "$this/orgs/",
            "m-user" to "$this/users/",
            "m-group" to "$this/groups/",
            "m-policy" to "$this/policies/",
        )
    }
}

fun prefixesFor(
    userId: String?=null,
    orgId: String?=null,
    repoId: String?=null,
    refId: String?=null,
    branchId: String?=null,
    commitId: String?=null,
    lockId: String?=null,
    diffId: String?=null,
    transactionId: String?=null,
    source: PrefixMapBuilder?= SPARQL_PREFIXES
): PrefixMapBuilder {
    return PrefixMapBuilder(source) {
        if(null != userId) {
            with("$ROOT_CONTEXT/users/$userId") {
                add(
                    "mu" to this,
                )
            }
        }

        if(null != orgId) {
            with("$ROOT_CONTEXT/orgs/$orgId") {
                add(
                    "mo" to this,
                )

                if(null != repoId) {
                    with("$this/repos/$repoId") {
                        add(
                            "mor" to this,
                            "mor-commit" to "$this/commits/",
                            "mor-branch" to "$this/branches/",
                            "mor-lock" to "$this/locks/",
                            "mor-snapshot" to "$this/snapshots/",
                            "mor-graph" to "$this/graphs/",
                        )

                        if(null != branchId) {
                            with("$this/branches/$branchId") {
                                add(
                                    "morb" to this,
                                )
                            }
                        }

                        if(null != commitId) {
                            with("$this/commits/$commitId") {
                                add(
                                    "morc" to this,
                                    "morc-lock" to "$this/locks",
                                    "morc-data" to "$this/data",
                                )

                                if(null != lockId) {
                                    with("$this/locks/$lockId") {
                                        add(
                                            "morcl" to this,
                                        )
                                    }

                                    if(null !== diffId) {
                                        with("$this/diffs/${diffId}") {
                                            add(
                                                "morcld" to this,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if(null != transactionId) {
            with("$ROOT_CONTEXT/transactions/$transactionId") {
                add(
                    "mt" to this,
                )
            }
        }

        this
    }
}


object MMS {
    private val BASE = SPARQL_PREFIXES["mms"]!!
    val uri = BASE

    // classes
    val Org = ResourceImpl("${BASE}Org")
    val Repo = ResourceImpl("${BASE}Repo")
    val Collection = ResourceImpl("${BASE}Collection")
    val Snapshot = ResourceImpl("${BASE}Snapshot")
    val Update = ResourceImpl("${BASE}Update")
    val Load = ResourceImpl("${BASE}Load")
    val Commit = ResourceImpl("${BASE}Commit")
    val Branch = ResourceImpl("${BASE}Branch")
    val Lock = ResourceImpl("${BASE}Lock")
    val Diff = ResourceImpl("${BASE}Diff")

    val User = ResourceImpl("${BASE}User")
    val Group = ResourceImpl("${BASE}Group")
    val Policy = ResourceImpl("${BASE}Policy")

    // object properties
    val id  = PropertyImpl("${BASE}id")

    // transaction properties
    val created = PropertyImpl("${BASE}created")
    val createdBy = PropertyImpl("${BASE}createdBy")
    val serviceId = PropertyImpl("${BASE}serviceId")
    val org = PropertyImpl("${BASE}org")
    val repo = PropertyImpl("${BASE}repo")
    val user = PropertyImpl("${BASE}user")
    val completed = PropertyImpl("${BASE}completed")
    val requestBody = PropertyImpl("${BASE}requestBody")
    val requestPath = PropertyImpl("${BASE}requestPath")

    val orgId = PropertyImpl("${BASE}orgId")
    val repoId = PropertyImpl("${BASE}repoId")
    val commitId = PropertyImpl("${BASE}commitId")

    // access control properties
    val implies = PropertyImpl("${BASE}implies")

    val ref = PropertyImpl("${BASE}ref")
    val commit = PropertyImpl("${BASE}commit")
    val graph = PropertyImpl("${BASE}graph")

    val diffSrc = PropertyImpl("${BASE}diffSrc")
    val diffDst = PropertyImpl("${BASE}diffDst")

    private val BASE_TXN = "${BASE}txn."
    object TXN {
        val stagingGraph = PropertyImpl("${BASE_TXN}stagingGraph")
        val baseModel = PropertyImpl("${BASE_TXN}baseModel")
        val baseModelGraph = PropertyImpl("${BASE_TXN}baseModelGraph")
        val sourceGraph = PropertyImpl("${BASE_TXN}baseModelGraph")
    }
}

object MMS_DATATYPE {
    private val BASE = SPARQL_PREFIXES["mms-datatype"]

    val commitMessage = BaseDatatype("${BASE}commitMessage")
    val sparql = BaseDatatype("${BASE}sparql")
}

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
    private val _BASE = SPARQL_PREFIXES["mms"]

    // classes
    val Org = ResourceImpl("${_BASE}Org")
    val Repo = ResourceImpl("${_BASE}Repo")
    val Collection = ResourceImpl("${_BASE}Collection")
    val Snapshot = ResourceImpl("${_BASE}Snapshot")
    val Update = ResourceImpl("${_BASE}Update")
    val Load = ResourceImpl("${_BASE}Load")
    val Commit = ResourceImpl("${_BASE}Commit")
    val Branch = ResourceImpl("${_BASE}Branch")
    val Lock = ResourceImpl("${_BASE}Lock")

    val User = ResourceImpl("${_BASE}User")
    val Group = ResourceImpl("${_BASE}Group")
    val Policy = ResourceImpl("${_BASE}Policy")

    // object properties
    val id  = PropertyImpl("${_BASE}id")

    // transaction properties
    val created = PropertyImpl("${_BASE}created")
    val createdBy = PropertyImpl("${_BASE}createdBy")
    val serviceId = PropertyImpl("${_BASE}serviceId")
    val org = PropertyImpl("${_BASE}org")
    val repo = PropertyImpl("${_BASE}repo")
    val user = PropertyImpl("${_BASE}user")
    val completed = PropertyImpl("${_BASE}completed")
    val requestBody = PropertyImpl("${_BASE}requestBody")
    val requestPath = PropertyImpl("${_BASE}requestPath")

    val orgId = PropertyImpl("${_BASE}orgId")
    val repoId = PropertyImpl("${_BASE}repoId")
    val commitId = PropertyImpl("${_BASE}commitId")

    // access control properties
    val implies = PropertyImpl("${_BASE}implies")

    val ref = PropertyImpl("${_BASE}ref")
    val commit = PropertyImpl("${_BASE}commit")
    val graph = PropertyImpl("${_BASE}graph")

    val checkout = PropertyImpl("${_BASE}checkout")
    val merge = PropertyImpl("${_BASE}merge")

    private val _TXN = "${_BASE}txn."
    object TXN {
        val stagingGraph = PropertyImpl("${_TXN}stagingGraph")
        val baseModel = PropertyImpl("${_TXN}baseModel")
        val baseModelGraph = PropertyImpl("${_TXN}baseModelGraph")
        val sourceGraph = PropertyImpl("${_TXN}baseModelGraph")
    }
}

object MMS_DATATYPE {
    private val _BASE = SPARQL_PREFIXES["mms-datatype"]

    val commitMessage = BaseDatatype("${_BASE}commitMessage")
    val sparql = BaseDatatype("${_BASE}sparql")
}

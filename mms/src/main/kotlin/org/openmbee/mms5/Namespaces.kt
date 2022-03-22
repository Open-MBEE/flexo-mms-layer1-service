package org.openmbee.mms5

import org.apache.jena.datatypes.BaseDatatype
import org.apache.jena.rdf.model.Property
import org.apache.jena.rdf.model.ResourceFactory
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

    fun find(namespace: String): String? {
        for(entry in map) {
            if(entry.value == namespace) return entry.key
        }

        return null;
    }

    fun terse(predicate: Property): String {
        val prefixId = find(predicate.nameSpace) ?: ""
        val suffix = predicate.uri.substring(0, prefixId.length)
        return if(prefixId.isNotEmpty()) "$prefixId:$suffix" else "<$suffix>"
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
    groupId: String?=null,
    orgId: String?=null,
    collectionId: String?=null,
    repoId: String?=null,
    refId: String?=null,
    branchId: String?=null,
    commitId: String?=null,
    lockId: String?=null,
    diffId: String?=null,
    transactionId: String?=null,
    source: PrefixMapBuilder?= SPARQL_PREFIXES,

    ldapId: String?=null,
): PrefixMapBuilder {
    return PrefixMapBuilder(source) {
        if(null != userId) {
            with("$ROOT_CONTEXT/users/$userId") {
                add(
                    "mu" to this,
                )
            }
        }

        if(null != groupId) {
            with("$ROOT_CONTEXT/group/$groupId") {
                add(
                    "mag" to this,
                )
            }
        }

        if(null != orgId) {
            with("$ROOT_CONTEXT/orgs/$orgId") {
                add(
                    "mo" to this,
                )

                if(null != collectionId) {
                    with("$this/collections/$collectionId") {
                        add(
                            "moc" to this,
                            "moc-graph" to "$this/graphs/",
                        )
                    }
                }

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

                        if(null !== diffId) {
                            with("$this/diffs/$diffId") {
                                add(
                                    "mord" to this,
                                )
                            }
                        }

                        if(null != lockId) {
                            with("$this/locks/$lockId") {
                                add(
                                    "morl" to this,
                                )
                            }
                        }

                        if(null != commitId) {
                            with("$this/commits/$commitId") {
                                add(
                                    "morc" to this,
                                )
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
    val Org = ResourceFactory.createResource("${BASE}Org")
    val Repo = ResourceFactory.createResource("${BASE}Repo")
    val Collection = ResourceFactory.createResource("${BASE}Collection")
    val Snapshot = ResourceFactory.createResource("${BASE}Snapshot")
    val Update = ResourceFactory.createResource("${BASE}Update")
    val Load = ResourceFactory.createResource("${BASE}Load")
    val Commit = ResourceFactory.createResource("${BASE}Commit")
    val Branch = ResourceFactory.createResource("${BASE}Branch")
    val Lock = ResourceFactory.createResource("${BASE}Lock")
    val Diff = ResourceFactory.createResource("${BASE}Diff")

    val User = ResourceFactory.createResource("${BASE}User")
    val Group = ResourceFactory.createResource("${BASE}Group")
    val Policy = ResourceFactory.createResource("${BASE}Policy")

    // object properties
    val id  = ResourceFactory.createProperty(BASE, "id")

    // transaction properties
    val created = ResourceFactory.createProperty(BASE, "created")
    val createdBy = ResourceFactory.createProperty(BASE, "createdBy")
    val serviceId = ResourceFactory.createProperty(BASE, "serviceId")
    val org = ResourceFactory.createProperty(BASE, "org")
    val repo = ResourceFactory.createProperty(BASE, "repo")
    val user = ResourceFactory.createProperty(BASE, "user")
    val completed = ResourceFactory.createProperty(BASE, "completed")
    val requestBody = ResourceFactory.createProperty(BASE, "requestBody")
    val requestPath = ResourceFactory.createProperty(BASE, "requestPath")

    val commitId = ResourceFactory.createProperty(BASE, "commitId")

    // access control properties
    val implies = ResourceFactory.createProperty(BASE, "implies")


    val srcRef = ResourceFactory.createProperty(BASE, "srcRef")
    val dstRef = ResourceFactory.createProperty(BASE, "dstRef")

    val etag = ResourceFactory.createProperty(BASE, "etag")
    val ref = ResourceFactory.createProperty(BASE, "ref")
    val collects = ResourceFactory.createProperty(BASE, "collects")
    val commit = ResourceFactory.createProperty(BASE, "commit")
    val graph = ResourceFactory.createProperty(BASE, "graph")
    val snapshot = ResourceFactory.createProperty(BASE, "snapshot")
    val parent = ResourceFactory.createProperty(BASE, "parent")
    val data = ResourceFactory.createProperty(BASE, "data")
    val body = ResourceFactory.createProperty(BASE, "body")
    val patch = ResourceFactory.createProperty(BASE, "patch")
    val delete = ResourceFactory.createProperty(BASE, "delete")
    val insert = ResourceFactory.createProperty(BASE, "insert")
    val where = ResourceFactory.createProperty(BASE, "where")

    val diffSrc = ResourceFactory.createProperty(BASE, "diffSrc")
    val diffDst = ResourceFactory.createProperty(BASE, "diffDst")

    private val BASE_TXN = "${BASE}txn."
    object TXN {
        val stagingGraph = ResourceFactory.createProperty(BASE_TXN, "stagingGraph")
        val baseModel = ResourceFactory.createProperty(BASE_TXN, "baseModel")
        val baseModelGraph = ResourceFactory.createProperty(BASE_TXN, "baseModelGraph")
        val sourceGraph = ResourceFactory.createProperty(BASE_TXN, "baseModelGraph")

        val diff = ResourceFactory.createProperty(BASE_TXN, "diff")
        val commitSource = ResourceFactory.createProperty(BASE_TXN, "commitSource")
        val diffInsGraph = ResourceFactory.createProperty(BASE_TXN, "diffInsGraph")
        val diffDelGraph = ResourceFactory.createProperty(BASE_TXN, "diffDelGraph")
    }
}

object MMS_DATATYPE {
    private val BASE = SPARQL_PREFIXES["mms-datatype"]

    val commitMessage = BaseDatatype("${BASE}commitMessage")
    val sparql = BaseDatatype("${BASE}sparql")
}

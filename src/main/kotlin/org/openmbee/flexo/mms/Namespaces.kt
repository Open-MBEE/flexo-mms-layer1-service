package org.openmbee.flexo.mms

import org.apache.jena.datatypes.BaseDatatype
import org.apache.jena.rdf.model.Property
import org.apache.jena.rdf.model.Resource
import org.apache.jena.rdf.model.ResourceFactory
import org.apache.jena.shared.PrefixMapping
import java.net.URLEncoder

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
            out, (key, value) -> out + "PREFIX $key: <$value>\n"
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

            "ma" to "$this/graphs/AccessControl.",
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
    policyId: String?=null,
    source: PrefixMapBuilder?= SPARQL_PREFIXES,
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
            with("$ROOT_CONTEXT/groups/${URLEncoder.encode(groupId, "UTF-8")}") {
                add(
                    "mg" to this,
                )
            }
        }

        if(null != policyId) {
            with("$ROOT_CONTEXT/policies/$policyId") {
                add("mp" to this)
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
                                    "morc-data" to "$this/data/",
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
    
    private fun res(id: String): Resource {
        return ResourceFactory.createResource("${BASE}${id}")
    }

    // classes
    val Org = res("Org")
    val Repo = res("Repo")
    val Collection = res("Collection")
    val Snapshot = res("Snapshot")
    val Model = res("Model")
    val Staging = res("Staging")
    val Update = res("Update")
    val Load = res("Load")
    val Commit = res("Commit")
    val Branch = res("Branch")
    val Lock = res("Lock")
    val Diff = res("Diff")

    val User = res("User")
    val Group = res("Group")
    val Policy = res("Policy")

    val Transaction = res("Transaction")


    val RepoMetadataGraph = res("RepoMetadataGraph")
    val SnapshotGraph = res("SnapshotGraph")
    val CollectionMetadataGraph = res("CollectionMetadataGraph")

    // object properties
    val id  = ResourceFactory.createProperty(BASE, "id")
    
    private fun prop(id: String): Property {
        return ResourceFactory.createProperty(BASE, id)
    }

    // ref/commit properties
    val createdBy = prop("createdBy")

    // transaction properties
    val created = prop("created")
    val serviceId = prop("serviceId")
    val org = prop("org")
    val repo = prop("repo")
    val branch = prop("branch")
    val collection = prop("collection")
    val user = prop("user")
    val completed = prop("completed")
    val requestBody = prop("requestBody")
    val requestPath = prop("requestPath")

    val commitId = prop("commitId")
    val submitted = prop("submitted")
    // access control properties
    val implies = prop("implies")


    val srcRef = prop("srcRef")
    val dstRef = prop("dstRef")

    val subject = prop("subject")
    val scope = prop("scope")
    val role = prop("role")

    val etag = prop("etag")
    val ref = prop("ref")
    val collects = prop("collects")
    val commit = prop("commit")
    val graph = prop("graph")
    val snapshot = prop("snapshot")
    val parent = prop("parent")
    val data = prop("data")
    val body = prop("body")
    val patch = prop("patch")
    val delete = prop("delete")
    val insert = prop("insert")
    val where = prop("where")

    val diffSrc = prop("diffSrc")
    val diffDst = prop("diffDst")

    private val BASE_TXN = "${BASE}txn."
    object TXN {
        private fun prop(id: String): Property {
            return ResourceFactory.createProperty(BASE_TXN, id)
        }

        val stagingGraph = prop("stagingGraph")
        val baseModel = prop("baseModel")
        val baseModelGraph = prop("baseModelGraph")
        val sourceGraph = prop("sourceGraph")

        val diff = prop("diff")
        val commitSource = prop("commitSource")
        val insGraph = prop("insGraph")
        val delGraph = prop("delGraph")
    }
}

object MMS_DATATYPE {
    private val BASE = SPARQL_PREFIXES["mms-datatype"]

    val commitMessage = BaseDatatype("${BASE}commitMessage")
    val sparql = BaseDatatype("${BASE}sparql")
    val sparqlGz = BaseDatatype("${BASE}sparqlGz")
}

object MMS_OBJECT {
    private val BASE = SPARQL_PREFIXES["mms-object"]

    private val BASE_ROLE = "${BASE}Role."
    object ROLE {
        private fun res(id: String): Resource {
            return ResourceFactory.createResource("${BASE_ROLE}${id}")
        }

        val AdminOrg = res("AdminOrg")
        val WriteOrg = res("WriteOrg")
        val ReadOrg = res("ReadOrg")
        val AdminRepo = res("AdminRepo")
        val WriteRepo = res("WriteRepo")
        val ReadRepo = res("ReadRepo")
        val AdminModel = res("AdminModel")
        val WriteModel = res("WriteModel")
        val ReadModel = res("ReadModel")
        val AdminMetadata = res("AdminMetadata")
        val WriteMetadata = res("WriteMetadata")
        val ReadMetadata = res("ReadMetadata")
        val AdminCluster = res("AdminCluster")
        val WriteCluster = res("WriteCluster")
        val ReadCluster = res("ReadCluster")
        val AdminAccessControl = res("AdminAccessControl")
        val WriteAccessControl = res("WriteAccessControl")
        val ReadAccessControl = res("ReadAccessControl")
    }
}

object MMS_URNS {
    private val mms = "urn:mms"

    object SUBJECT {
        val aggregator = "$mms:aggregator"
        val auth = "$mms:auth"
        val context = "$mms:context"
        val inspect = "$mms:inspect"
    }

    object PREDICATE {
        val pass = "$mms:pass"
        val policy = "$mms:policy"
    }
}
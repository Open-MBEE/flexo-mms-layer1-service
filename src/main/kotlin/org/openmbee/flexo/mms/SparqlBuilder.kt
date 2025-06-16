package org.openmbee.flexo.mms

import org.apache.jena.datatypes.xsd.XSDDatatype
import org.apache.jena.query.ParameterizedSparqlString
import org.apache.jena.rdf.model.Resource
import org.apache.jena.vocabulary.XSD
import java.time.Instant
import java.util.*


enum class UpdateOperationBlock(val id: String) {
    NONE(""),
    INSERT("INSERT"),
    DELETE("DELETE"),
    INSERT_DATA("INSERT_DATA"),
    DELETE_DATA("DELETE_DATA"),
    WHERE("WHERE"),
}

private val CODE_INDENT = "    "

private fun pp(insert: String, indentLevel: Int=2): String {
    return insert.prependIndent(CODE_INDENT.repeat(indentLevel)).trimStart()
}


/**
 * Order matters in SPARQL's OPTIONAL: https://github.com/blazegraph/database/wiki/SPARQL_Order_Matters
 */

private fun SPARQL_INSERT_TRANSACTION(customProperties: String?=null, subTxnId: String?=null): String {
    return """
        graph m-graph:Transactions {
            mt:${subTxnId?: ""} a mms:Transaction ;
                mms:created ?_now ;
                mms:serviceId ?_serviceId ;
                mms:requestPath ?_requestPath ;
                mms:requestMethod ?_requestMethod ;
                mms:authMethod ?__mms_authMethod ;
                mms:appliedGroupId ?__mms_groupId ;
                mms:permit ?_requiredPermission ;
                mms:appliedPolicy ?__mms_policy ;
                # mms:requestBody ?_requestBody ;
                mms:requestBodyContentType ?_requestBodyContentType ;
                ${pp(customProperties?: "", 4)}
                .
        }
    """.trimIndent()
}


/**
 * Returns the string if the given condition is true, otherwise empty string (or supplied value).
 * Equivalent to `if(condition) "$this" else ""`
 */
infix fun String.iff(condition: Boolean): String {
    return if(condition) this else ""
}

fun escapeLiteral(value: String): String {
    return ParameterizedSparqlString().apply{ appendLiteral(value) }.toString()
}

fun escapeIri(value: String): String {
    return ParameterizedSparqlString().apply{ appendIri(value) }.toString()
}

fun serializePairs(node: Resource): String {
    return ParameterizedSparqlString().apply {
        var prevPredicateUri = ""
        node.listProperties().forEach {
            val predicateUri = it.predicate.asResource().uri
            if(predicateUri != prevPredicateUri) {
                prevPredicateUri = predicateUri
                if(prevPredicateUri.isNotEmpty()) {
                    this.append(" ; ")
                }
                this.appendIri(predicateUri)
            }

            if(it.`object`.isLiteral) {
                val literal = it.`object`.asLiteral()

                if(literal.language != "") {
                    this.appendLiteral(literal.string, literal.language)
                }
                else if(literal.datatypeURI == XSD.xstring.uri) {
                    this.appendLiteral(literal.string)
                }
                else {
                    this.appendLiteral(literal.lexicalForm, literal.datatype)
                }
            }
            else if(it.`object`.isURIResource) {
                this.appendIri(it.`object`.asResource().uri)
            }
        }
    }.toString()
}

abstract class SparqlBuilder<out Instance: SparqlBuilder<Instance>>(private val indentLevel: Int=1) {
    protected val sparqlString = StringBuilder()

    fun raw(vararg sparql: String): Instance {
        sparqlString.append(sparql.joinToString("\n") { it.trimIndent()+"\n" })

        return this as Instance
    }

    override fun toString(): String {
        return pp(sparqlString.toString(), indentLevel)
    }
}

abstract class PatternBuilder<out Instance: SparqlBuilder<Instance>>(
    private val layer1: AnyLayer1Context,
    indentLevel: Int,
): SparqlBuilder<Instance>(indentLevel) {
    fun graph(graph: String, setup: GraphBuilder.() -> Unit): Instance {
        return raw("""
            graph $graph {
                ${GraphBuilder(layer1, 4).apply{ setup() }}
            }
       """)
    }

    fun group(setup: GroupBuilder.() -> Unit): Instance {
        return raw("""
            {
                ${GroupBuilder(layer1, 4).apply{ setup() }}
            }
        """)
    }
}

class GroupBuilder(
    layer1: AnyLayer1Context,
    indentLevel: Int,
): WhereBuilder(layer1, indentLevel)

class GraphBuilder(
    layer1: AnyLayer1Context,
    indentLevel: Int,
): SparqlBuilder<GraphBuilder>(indentLevel)

class KeyedValuesBuilder(
    layer1: AnyLayer1Context,
    indentLevel: Int,
): SparqlBuilder<KeyedValuesBuilder>(indentLevel) {
    private val block = StringBuilder()

    fun literal(vararg list: String) {
        for(value in list) {
            block.append("${ParameterizedSparqlString().appendLiteral(value)} ")
        }
    }

    override fun toString(): String {
        return block.toString()
    }
}

class ValuesBuilder(
    private val layer1: AnyLayer1Context,
    private val indentLevel: Int,
): SparqlBuilder<ValuesBuilder>(indentLevel) {
    fun key(key: String, setup: KeyedValuesBuilder.()->Unit): ValuesBuilder {
        return raw("""
            values ?$key {
                ${KeyedValuesBuilder(layer1, indentLevel).apply{ setup }}
            }
        """)
    }
}

open class WhereBuilder(
    private val layer1: AnyLayer1Context,
    private val indentLevel: Int,
): PatternBuilder<WhereBuilder>(layer1, indentLevel) {
    fun txnOrInspections(subTxnId: String?=null, localConditions: ConditionsGroup, setup: SparqlBuilder<WhereBuilder>.() -> Unit): WhereBuilder {
        // first group in a series of unions fetches intended outputs
        group {
            // all the details about this transaction
            txn(subTxnId)

            // details relevant to resource
            setup()
        }
        // all subsequent unions are for inspecting what if any conditions failed
        return raw("""
            # in case the transaction failed, deduce which conditions did not pass
            union {
                # only match inspections if transaction failed
                filter not exists {
                    graph m-graph:Transactions {
                        mt: ?mt_p ?mt_o .
                    }
                }

                # inspections to deduce which condition(s) failed
                ${localConditions.unionInspectPatterns().reindent(4)}
            }
        """)
    }

    fun txn(subTxnId: String?=null, noAccessControl: Boolean=false): WhereBuilder {
        return raw("""
            # match a successful transaction and its details
            graph m-graph:Transactions {
                mt:${subTxnId?: ""} ?mt_p ?mt_o ;
                    ${"mms:appliedPolicy ?__mms_policy ;" iff !noAccessControl}
                    .
                
                # a policy might have been created during this transaction
                optional {
                    mt:${subTxnId?: ""} mms:createdPolicy ?__mms_createdPolicy .
                }
            }
            
            ${"""
                # include the applied policy details
                graph m-graph:AccessControl.Policies {
                    ?__mms_policy ?__mms_policy_p ?__mms_policy_o .
                    
                    # in case a policy was also created during this transaction
                    optional {
                        ?__mms_createdPolicy ?__mms_createdPolicy_p ?__mms_createdPolicy_o .
                    }
                }
            """.reindent(3) iff !noAccessControl}
            
        """)
    }

    fun auth(scope: String?="mo", conditions: ConditionsGroup?=null): WhereBuilder {
        return raw("""
            ${conditions?.requiredPatterns()?.joinToString("\n") ?: ""}

            optional {
                graph m-graph:AccessControl.Policies {
                    ?__mms_policy mms:scope ${scope?: "mo"}: ;
                        ?__mms_policy_p ?__mms_policy_o .
                }
            }
        """)
    }

    fun etag(subject: String): WhereBuilder {
        return raw("""
            graph mor-graph:Metadata {
                $subject mms:etag ?__mms_etag .
            }
        """)
    }

    fun values(setup: ValuesBuilder.() -> Unit): ValuesBuilder {
        return ValuesBuilder(layer1, indentLevel).apply { setup }
    }
}

class DeleteBuilder(
    layer1: AnyLayer1Context,
    indentLevel: Int,
): PatternBuilder<DeleteBuilder>(layer1, indentLevel)

class TxnBuilder(
    private val insertBuilder: InsertBuilder,
) {
    fun autoPolicy(scope: Scope, vararg roles: Role) {
        insertBuilder.run {
            val policyCurie = "m-policy:Auto${scope.type}Owner.${UUID.randomUUID()}"

            graph("m-graph:Transactions") {
                raw("""
                    mt: mms:createdPolicy $policyCurie .
                """)
            }

            graph("m-graph:AccessControl.Policies") {
                raw("""
                    $policyCurie a mms:Policy ;
                        mms:subject mu: ;
                        mms:scope ${scope.id}: ;
                        mms:role ${roles.joinToString(",") { "mms-object:Role.${it.id}" }}  ;
                        .
                """)
            }
        }
    }

    fun replacesExisting(replacesExisting: Boolean) {
        insertBuilder.run {
            graph("m-graph:Transactions") {
                raw("""
                    mt: mms:replacesExisting $replacesExisting .
                """)
            }
        }
    }
}

class InsertBuilder(
    private val layer1: AnyLayer1Context,
    val indentLevel: Int,
): PatternBuilder<InsertBuilder>(layer1, indentLevel) {
    fun txn(vararg extras: Pair<String, String>, setup: (TxnBuilder.() -> Unit)?=null): InsertBuilder {
        return subtxn("", extras.toMap(), setup)
    }

    fun subtxn(subTxnId: String, extras: Map<String, String>?=null, setup: (TxnBuilder.() -> Unit)?=null): InsertBuilder {
        val properties = extras?.toMutableMap()?: mutableMapOf()
        if(null != layer1.userId) properties["mms:user"] = "mu:"
        if(null != layer1.orgId) properties["mms:org"] = "mo:"
        if(null != layer1.repoId) properties["mms:repo"] = "mor:"
        if(null != layer1.branchId) properties["mms:branch"] = "morb:"
        if(null != layer1.scratchId) properties["mms:scratch"] = "mors:"

        val propertiesSparql = properties.entries.fold("") { out, (pred, obj) -> "$out$pred $obj ;\n" }

        raw(SPARQL_INSERT_TRANSACTION(propertiesSparql, subTxnId))

        if(setup != null) {
            TxnBuilder(this).setup()
        }

        return this
    }

    fun values(setup: ValuesBuilder.() -> Unit): ValuesBuilder {
        return ValuesBuilder(layer1, indentLevel).apply(setup)
    }
}

class InsertDataBuilder(
    layer1: AnyLayer1Context,
    indentLevel: Int,
): PatternBuilder<InsertDataBuilder>(layer1, indentLevel)

class ConstructBuilder(
    private val layer1: AnyLayer1Context,
    indentLevel: Int,
): PatternBuilder<ConstructBuilder>(layer1, indentLevel) {
    fun txn(subTxnId: String?=null): ConstructBuilder {
        // complete transaction and access control details
        raw("""
            # transaction metadata
            mt:${subTxnId?: ""} ?mt_p ?mt_o .
            
            # details which policy was applied
            ?__mms_policy ?__mms_policy_p ?__mms_policy_o .
            
            # details about created policy
            ?__mms_createdPolicy ?__mms_createdPolicy_p ?__mms_createdPolicy_o .
            
        """)

        // inspect which conditions passed
        inspections()

        // chain
        return this
    }

    fun inspections(): ConstructBuilder {
        return raw("""
            # inspections allow deducing which, if any, conditions failed
            <${MMS_URNS.SUBJECT.inspect}> <${MMS_URNS.PREDICATE.pass}> ?__mms_inspect_pass .
            
        """)
    }

    fun etag(subject: String): ConstructBuilder {
        return raw("""
            $subject mms:etag ?__mms_etag .
        """)
    }
}


class QueryBuilder(
    private val layer1: AnyLayer1Context,
    indentLevel: Int=0,
): SparqlBuilder<QueryBuilder>(indentLevel) {
    fun ask(setup: WhereBuilder.() -> Unit): QueryBuilder {
        return raw("""
            ask {
                ${WhereBuilder(layer1, 4).apply{ setup() }}
            }
        """)
    }

    fun construct(setup: ConstructBuilder.() -> Unit): QueryBuilder {
        return raw("""
            construct {
                ${ConstructBuilder(layer1, 4).apply{ setup() }}    
            }
        """)
    }

    fun where(setup: WhereBuilder.() -> Unit): QueryBuilder {
        return raw("""
            where {
                ${WhereBuilder(layer1, 4).apply{ setup() }}    
            }
        """)
    }
}

class ComposeUpdateBuilder(
    private val layer1: AnyLayer1Context,
    private val indentLevel: Int=0,
): PatternBuilder<ComposeUpdateBuilder>(layer1, indentLevel) {
    var deleteString = ""
    var insertString = ""
    var whereString = ""

    fun txn(vararg extras: Pair<String, String>, setup: (TxnBuilder.() -> Unit)?=null) {
        insertString += InsertBuilder(layer1, indentLevel).apply {
            txn(*extras)
        }

        whereString += WhereBuilder(layer1, indentLevel).apply {
            txn(*extras)
        }
    }

    fun conditions(conditions: ConditionsGroup) {
        insertString += raw(*conditions.requiredPatterns())
    }

    fun delete(setup: DeleteBuilder.() -> DeleteBuilder) {
        deleteString += DeleteBuilder(layer1, indentLevel).setup()
    }

    fun insert(setup: InsertBuilder.() -> InsertBuilder) {
        insertString += InsertBuilder(layer1, indentLevel).setup()
    }

    fun where(setup: WhereBuilder.() -> WhereBuilder) {
        whereString += WhereBuilder(layer1, indentLevel).setup()
    }

    override fun toString(): String {
        return """
            delete {
                $deleteString
            }
            insert {
                $insertString
            }
            where {
                $whereString
            }
        """
    }
}

class LostUpdateOperationException: Exception("An update operation was lost")

class UpdateBuilder(
    private val layer1: AnyLayer1Context,
    indentLevel: Int=0,
): SparqlBuilder<UpdateBuilder>(indentLevel) {
    var operationCount = 0
    var previousBlock = UpdateOperationBlock.NONE

    var pendingDeleteString = ""
    var pendingInsertString = ""
    var pendingInsertDataString = ""
    var pendingWhereString = ""

    fun compose(setup: ComposeUpdateBuilder.() -> ComposeUpdateBuilder): UpdateBuilder {
        return raw("${ComposeUpdateBuilder(layer1, 4).setup()}")
    }

    fun delete(setup: DeleteBuilder.() -> Unit): UpdateBuilder {
        if(operationCount++ > 0) raw(";")

        previousBlock = UpdateOperationBlock.DELETE

        return raw("""
            delete {
                ${DeleteBuilder(layer1, 4).apply{ setup() }}
                
                $pendingDeleteString
            }
        """).apply {
            pendingDeleteString = ""
        }
    }

    fun insert(setup: InsertBuilder.() -> Unit): UpdateBuilder {
        if(previousBlock != UpdateOperationBlock.DELETE) {
            if(operationCount++ > 0) raw(";")
        }

        previousBlock = UpdateOperationBlock.INSERT

        return raw("""
            insert {
                ${InsertBuilder(layer1, 4).apply{ setup() }}
                
                $pendingInsertString
            }
        """).apply {
            pendingInsertString = ""
        }
    }

    fun insertData(setup: InsertDataBuilder.() -> Unit): UpdateBuilder {
        if(operationCount++ > 0) raw(";")

        previousBlock = UpdateOperationBlock.INSERT_DATA

        return raw("""
            insert data {
                ${InsertDataBuilder(layer1, 4).apply{ setup() }}
                
                $pendingInsertDataString
            }
        """).apply {
            pendingInsertDataString = ""
        }
    }

    fun where(setup: WhereBuilder.() -> Unit): UpdateBuilder {
        previousBlock = UpdateOperationBlock.WHERE

        return raw("""
            where {
                ${WhereBuilder(layer1, 4).apply{ setup() }}
                
                $pendingWhereString
            }
        """).apply {
            pendingWhereString = ""
        }
    }

    override fun toString(): String {
        return toString { this }
    }

    fun toString(config: SparqlParameterizer.() -> SparqlParameterizer): String {
        return parameterizedSparql(sparqlString.toString()) {
            if(pendingDeleteString.isNotEmpty() || pendingInsertString.isNotEmpty() || pendingInsertDataString.isNotEmpty() || pendingWhereString.isNotEmpty()) {
                throw LostUpdateOperationException()
            }

            // prefixes(mms.prefixes)

            literal(
                "_userId" to (layer1.userId?: ""),
                "_orgId" to (layer1.orgId?: ""),
                "_repoId" to (layer1.repoId?: ""),
                "_branchId" to (layer1.branchId?: ""),
                "_commitId" to layer1.commitId,
                "_transactionId" to layer1.transactionId,
                "_serviceId" to SERVICE_ID,
                "_requestPath" to layer1.requestPath,
                "_requestMethod" to layer1.requestMethod,
//                "_requestBody" to mms.requestBody,
                "_requestBodyContentType" to layer1.requestBodyContentType,
            )

            datatyped(
                "_now" to (Instant.now().toString() to XSDDatatype.XSDdateTime),
                "_commitMessage" to ((layer1.commitMessage?: "") to MMS_DATATYPE.commitMessage),
            )

            config()
        }
    }
}

fun String.reindent(width: Int): String {
    return "\n"+this.trimIndent().prependIndent("    ".repeat(width))
}

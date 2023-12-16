package org.openmbee.flexo.mms

import org.apache.jena.datatypes.xsd.XSDDatatype
import org.apache.jena.query.ParameterizedSparqlString
import org.apache.jena.rdf.model.Resource
import org.apache.jena.vocabulary.XSD
import java.time.Instant
import java.util.*


enum class UpdateOperationBlock(val id: String) {
    NONE(""),
    DELETE("DELETE"),
    INSERT("INSERT"),
    WHERE("WHERE"),
}

private val CODE_INDENT = "    "

private fun pp(insert: String, indentLevel: Int=2): String {
    return insert.prependIndent(CODE_INDENT.repeat(indentLevel)).trimStart()
}


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
                # mms:requestBody ?_requestBody ;
                mms:requestBodyContentType ?_requestBodyContentType ;
                ${pp(customProperties?: "", 4)}
                .
        }
    """.trimIndent()
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
    fun txn(subTxnId: String?=null, scope: String?=null): WhereBuilder {
        auth(scope)
        return raw("""
            graph m-graph:Transactions {
                mt:${subTxnId?: ""} ?mt_p ?mt_o .
            }
        """)
    }

    fun auth(scope: String?="mo", conditions: ConditionsGroup?=null): WhereBuilder {
        return raw("""
            optional {
                graph m-graph:AccessControl.Policies {
                    ?__mms_policy mms:scope ${scope?: "mo"}: ;
                        ?__mms_policy_p ?__mms_policy_o .
                }
            }
            
            ${conditions?.requiredPatterns()?.joinToString("\n") ?: ""}
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
            graph("m-graph:AccessControl.Policies") {
                raw(
                    """
                    m-policy:Auto${scope.type}Owner.${UUID.randomUUID()} a mms:Policy ;
                        mms:subject mu: ;
                        mms:scope ${scope.id}: ;
                        mms:role ${roles.joinToString(",") { "mms-object:Role.${it.id}" }}  ;
                        .
                """
                )
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

        val propertiesSparql = properties.entries.fold("") { out, (pred, obj) -> "$out$pred $obj ;\n" }

        raw(SPARQL_INSERT_TRANSACTION(propertiesSparql, subTxnId))

        if(setup != null) {
            TxnBuilder(this).setup()
        }

        return this
    }

    fun values(setup: ValuesBuilder.() -> Unit): ValuesBuilder {
        return ValuesBuilder(layer1, indentLevel).apply { setup }
    }
}


class ConstructBuilder(
    private val layer1: AnyLayer1Context,
    indentLevel: Int,
): PatternBuilder<ConstructBuilder>(layer1, indentLevel) {
    fun txn(subTxnId: String?=null): ConstructBuilder {
        auth()
        return raw("""
            mt:${subTxnId?: ""} ?mt_p ?mt_o .
        """)
    }

    fun auth(): ConstructBuilder {
        return raw("""
            ?__mms_policy ?__mms_policy_p ?__mms_policy_o .
            
            <urn:mms:inspect> <urn:mms:pass> ?__mms_inspect_pass .
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

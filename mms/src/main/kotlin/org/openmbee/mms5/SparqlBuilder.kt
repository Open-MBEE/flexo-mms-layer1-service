package org.openmbee.mms5

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


private fun SPARQL_INSERT_TRANSACTION(customProperties: String?=null): String {
    return """
        graph m-graph:Transactions {
            mt: a mms:Transaction ;
                mms:created ?_now ;
                mms:serviceId ?_serviceId ;
                mms:requestPath ?_requestPath ;
                mms:requestMethod ?_requestMethod ;
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
    private val mms: MmsL1Context,
    indentLevel: Int,
): SparqlBuilder<Instance>(indentLevel) {
    fun graph(graph: String, setup: GraphBuilder.() -> Unit): Instance {
        return raw("""
            graph $graph {
                ${GraphBuilder(mms, 4).apply{ setup() }}
            }
       """)
    }

    fun group(setup: GroupBuilder.() -> Unit): Instance {
        return raw("""
            {
                ${GroupBuilder(mms, 4).apply{ setup() }}
            }
        """)
    }
}

class GroupBuilder(
    mms: MmsL1Context,
    indentLevel: Int,
): WhereBuilder(mms, indentLevel)

class GraphBuilder(
    mms: MmsL1Context,
    indentLevel: Int,
): SparqlBuilder<GraphBuilder>(indentLevel)

class KeyedValuesBuilder(
    mms: MmsL1Context,
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
    private val mms: MmsL1Context,
    private val indentLevel: Int,
): SparqlBuilder<ValuesBuilder>(indentLevel) {
    fun key(key: String, setup: KeyedValuesBuilder.()->Unit): ValuesBuilder {
        return raw("""
            values ?$key {
                ${KeyedValuesBuilder(mms, indentLevel).apply{ setup }}
            }
        """)
    }
}

open class WhereBuilder(
    private val mms: MmsL1Context,
    private val indentLevel: Int,
): PatternBuilder<WhereBuilder>(mms, indentLevel) {
    fun txn(): WhereBuilder {
        return raw("""
            graph m-graph:Transactions {
                mt: ?mt_p ?mt_o .
            }
            
            graph m-graph:AccessControl.Policies {
                optional {
                    ?policy mms:scope mo: ;
                        ?policy_p ?policy_o .
                }
            }    
        """)
    }

    fun values(setup: ValuesBuilder.() -> Unit): ValuesBuilder {
        return ValuesBuilder(mms, indentLevel).apply { setup }
    }


    fun groupDns(): WhereBuilder {
        return raw("")
        // return raw("""
        //     values ?_ldapGroupDns {
        //         ${mms.groups.joinToString(" ") { escapeLiteral(it) }}
        //     }
        // """)
    }
}

class DeleteBuilder(
    mms: MmsL1Context,
    indentLevel: Int,
): PatternBuilder<DeleteBuilder>(mms, indentLevel)

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
    private val mms: MmsL1Context,
    val indentLevel: Int,
): PatternBuilder<InsertBuilder>(mms, indentLevel) {
    fun txn(vararg extras: Pair<String, String>, setup: (TxnBuilder.() -> Unit)?=null): InsertBuilder {
        val properties = extras.toMap().toMutableMap()
        if(null != mms.userId) properties["mms:user"] = "mu:"
        if(null != mms.orgId) properties["mms:org"] = "mo:"
        if(null != mms.repoId) properties["mms:repo"] = "mor:"
        if(null != mms.branchId) properties["mms:branch"] = "morb:"

        val propertiesSparql = properties.entries.fold("") { out, (pred, obj) -> "$out$pred $obj ;\n" }

        raw(SPARQL_INSERT_TRANSACTION(propertiesSparql))

        if(setup != null) {
            TxnBuilder(this).setup()
        }

        return this
    }

    fun values(setup: ValuesBuilder.() -> Unit): ValuesBuilder {
        return ValuesBuilder(mms, indentLevel).apply { setup }
    }
}


class ConstructBuilder(
    private val mms: MmsL1Context,
    indentLevel: Int,
): PatternBuilder<ConstructBuilder>(mms, indentLevel) {
    fun txn(): ConstructBuilder {
        return raw("""
            mt: ?mt_p ?mt_o .
            
            ?policy ?policy_p ?policy_o .
            
            <mms://inspect> <mms://pass> ?inspect .
        """)
    }
}


class QueryBuilder(
    private val mms: MmsL1Context,
    indentLevel: Int=0,
): SparqlBuilder<QueryBuilder>(indentLevel) {
    fun construct(setup: ConstructBuilder.() -> Unit): QueryBuilder {
        return raw("""
            construct {
                ${ConstructBuilder(mms, 4).apply{ setup() }}    
            }
        """)
    }

    fun where(setup: WhereBuilder.() -> Unit): QueryBuilder {
        return raw("""
            where {
                ${WhereBuilder(mms, 4).apply{ setup() }}    
            }
        """)
    }
}

class ComposeUpdateBuilder(
    private val mms: MmsL1Context,
    private val indentLevel: Int=0,
): PatternBuilder<ComposeUpdateBuilder>(mms, indentLevel) {
    var deleteString = ""
    var insertString = ""
    var whereString = ""

    fun txn(vararg extras: Pair<String, String>, setup: (TxnBuilder.() -> Unit)?=null) {
        insertString += InsertBuilder(mms, indentLevel).apply {
            txn(*extras)
        }

        whereString += WhereBuilder(mms, indentLevel).apply {
            txn(*extras)
        }
    }

    fun conditions(conditions: ConditionsGroup) {
        insertString += raw(*conditions.requiredPatterns())
    }

    fun delete(setup: DeleteBuilder.() -> DeleteBuilder) {
        deleteString += DeleteBuilder(mms, indentLevel).setup()
    }

    fun insert(setup: InsertBuilder.() -> InsertBuilder) {
        insertString += InsertBuilder(mms, indentLevel).setup()
    }

    fun where(setup: WhereBuilder.() -> WhereBuilder) {
        whereString += WhereBuilder(mms, indentLevel).setup()
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
    private val mms: MmsL1Context,
    indentLevel: Int=0,
): SparqlBuilder<UpdateBuilder>(indentLevel) {
    var operationCount = 0
    var previousBlock = UpdateOperationBlock.NONE

    var pendingDeleteString = ""
    var pendingInsertString = ""
    var pendingInsertDataString = ""
    var pendingWhereString = ""

    fun compose(setup: ComposeUpdateBuilder.() -> ComposeUpdateBuilder): UpdateBuilder {
        return raw("${ComposeUpdateBuilder(mms, 4).setup()}")
    }

    fun delete(setup: DeleteBuilder.() -> Unit): UpdateBuilder {
        if(operationCount++ > 0) raw(";")

        previousBlock = UpdateOperationBlock.DELETE

        return raw("""
            delete {
                ${DeleteBuilder(mms, 4).apply{ setup() }}
                
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
                ${InsertBuilder(mms, 4).apply{ setup() }}
                
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
                ${WhereBuilder(mms, 4).apply{ setup() }}
                
                $pendingWhereString
            }
        """).apply {
            pendingWhereString = ""
        }
    }

    override fun toString(): String {
        return toString { this }
    }

    fun toString(config: Parameterizer.() -> Parameterizer): String {
        return parameterizedSparql(sparqlString.toString()) {
            if(pendingDeleteString.isNotEmpty() || pendingInsertString.isNotEmpty() || pendingInsertDataString.isNotEmpty() || pendingWhereString.isNotEmpty()) {
                throw LostUpdateOperationException()
            }

            // prefixes(mms.prefixes)

            literal(
                "_userId" to (mms.userId?: ""),
                "_orgId" to (mms.orgId?: ""),
                "_repoId" to (mms.repoId?: ""),
                "_branchId" to (mms.branchId?: ""),
                "_commitId" to mms.commitId,
                "_transactionId" to mms.transactionId,
                "_serviceId" to SERVICE_ID,
                "_requestPath" to mms.requestPath,
                "_requestMethod" to mms.requestMethod,
                "_requestBody" to mms.requestBody,
                "_requestBodyContentType" to mms.requestBodyContentType,
            )

            datatyped(
                "_now" to (Instant.now().toString() to XSDDatatype.XSDdateTime),
                "_commitMessage" to ((mms.commitMessage?: "") to MMS_DATATYPE.commitMessage),
            )

            config()
        }
    }
}

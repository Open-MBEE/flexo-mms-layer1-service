package org.openmbee

import io.ktor.request.*
import org.apache.jena.datatypes.xsd.XSDDatatype
import java.time.Instant
import java.util.*
import javax.management.Query


private val CODE_INDENT = "    "

fun pp(insert: String, indentLevel: Int=2): String {
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
                mms:requestBody ?_requestBody ;
                mms:requestBodyContentType ?_requestBodyContentType ;
                ${pp(customProperties?: "", 4)}
                .
        }
    """.trimIndent()
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
    fun graph(graph: String, setup: GraphBuilder.() -> GraphBuilder): Instance {
        return raw("""
            graph $graph {
                ${GraphBuilder(mms, 4).setup()}
            }
       """)
    }

    fun group(setup: GroupBuilder.() -> Unit): Instance {
        return raw("""
            {
                ${GroupBuilder(mms, 4).setup()}
            }
        """)
    }
}

class GroupBuilder(
    mms: MmsL1Context,
    indentLevel: Int,
): SparqlBuilder<GroupBuilder>(indentLevel)

class GraphBuilder(
    mms: MmsL1Context,
    indentLevel: Int,
): SparqlBuilder<GraphBuilder>(indentLevel)

class WhereBuilder(
    mms: MmsL1Context,
    indentLevel: Int,
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
    indentLevel: Int,
): PatternBuilder<InsertBuilder>(mms, indentLevel) {
    fun txn(vararg extras: Pair<String, String>, setup: (TxnBuilder.() -> Unit)?): InsertBuilder {
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
                ${ConstructBuilder(mms, 4).setup()}    
            }
        """)
    }

    fun where(setup: WhereBuilder.() -> Unit): QueryBuilder {
        return raw("""
            where {
                ${WhereBuilder(mms, 4).setup()}    
            }
        """)
    }
}


class UpdateBuilder(
    private val mms: MmsL1Context,
    indentLevel: Int=0,
): SparqlBuilder<UpdateBuilder>(indentLevel) {
    var operationCount = 0
    var previousBlock = OperationBlock.NONE

    fun delete(setup: DeleteBuilder.() -> DeleteBuilder): UpdateBuilder {
        if(operationCount++ > 0) raw(";")

        previousBlock = OperationBlock.DELETE

        return raw("""
            delete {
                ${DeleteBuilder(mms, 4).setup()}
            }
        """)
    }

    fun insert(setup: InsertBuilder.() -> InsertBuilder): UpdateBuilder {
        if(previousBlock != OperationBlock.DELETE) {
            if(operationCount++ > 0) raw(";")
        }

        previousBlock = OperationBlock.INSERT

        return raw("""
            insert {
                ${InsertBuilder(mms, 4).setup()}
            }
        """)
    }

    fun insertData(setup: InsertBuilder.() -> InsertBuilder): UpdateBuilder {
        if(previousBlock != OperationBlock.DELETE) {
            if(operationCount++ > 0) raw(";")
        }

        previousBlock = OperationBlock.INSERT

        return raw("""
            insert data {
                ${InsertBuilder(mms, 4).setup()}
            }
        """)
    }

    fun where(setup: WhereBuilder.() -> WhereBuilder): UpdateBuilder {
        previousBlock = OperationBlock.WHERE

        return raw("""
            where {
                ${WhereBuilder(mms, 4).setup()}
            }
        """)
    }

    override fun toString(): String {
        return toString { this }
    }

    fun toString(config: Parameterizer.() -> Parameterizer): String {
        return parameterizedSparql(sparqlString.toString()) {
            prefixes(mms.prefixes)

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

enum class OperationBlock(val id: String) {
    NONE(""),
    DELETE("DELETE"),
    INSERT("INSERT"),
    WHERE("WHERE"),
}

class TransactionContext(
    var userId: String?=null,
    var orgId: String?=null,
    var repoId: String?=null,
    var branchId: String?=null,
    commitId: String?=null,
    var lockId: String?=null,
    var diffId: String?=null,
    var request: ApplicationRequest,
    var commitMessage: String?=null,
    val requestBody: String="",
) {
    val transactionId = UUID.randomUUID().toString()
    val commitId = commitId ?: transactionId
    val requestPath = request.path()
    val requestMethod = request.httpMethod.value
    val requestBodyContentType = request.contentType().toString()

    var operationCount = 0
    var previousBlock = OperationBlock.NONE

    val prefixes: PrefixMapBuilder
        get() = prefixesFor(
            userId = userId,
            orgId = orgId,
            repoId = repoId,
            branchId = branchId,
            commitId = commitId,
            lockId = lockId,
            diffId = diffId,
            transactionId = transactionId,
        )

    fun update(setup: UpdateBuilder.() -> UpdateBuilder): UpdateBuilder {
        return UpdateBuilder(this,).setup()
    }
}

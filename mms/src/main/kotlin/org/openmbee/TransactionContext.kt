package org.openmbee

import io.ktor.client.request.*
import io.ktor.request.*
import org.apache.jena.datatypes.xsd.XSDDatatype
import org.apache.jena.graph.Graph
import org.apache.jena.update.Update
import java.time.Instant
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap


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
                mms:requestBody ?_requestBody ;
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
    private val context: TransactionContext,
    indentLevel: Int,
): SparqlBuilder<Instance>(indentLevel) {
    fun graph(graph: String, setup: GraphBuilder.() -> GraphBuilder): Instance {
        return raw("""
            graph $graph {
                ${GraphBuilder(context, 4).setup()}
            }
       """)
    }
}

class GraphBuilder(
    private val context: TransactionContext,
    indentLevel: Int,
): SparqlBuilder<GraphBuilder>(indentLevel)

class WhereBuilder(
    private val context: TransactionContext,
    indentLevel: Int,
): PatternBuilder<WhereBuilder>(context, indentLevel)

class InsertBuilder(
    private val context: TransactionContext,
    indentLevel: Int,
): PatternBuilder<InsertBuilder>(context, indentLevel) {
    fun txn(properties: MutableMap<String, String> = HashMap()): InsertBuilder {
        if(null != context.userId) properties["mms:user"] = "mu:"
        if(null != context.orgId) properties["mms:org"] = "mo:"
        if(null != context.projectId) properties["mms:project"] = "mp:"
        if(null != context.branchId) properties["mms:branch"] = "mpb:"

        val propertiesSparql = properties.entries.fold("") { out, (pred, obj) -> "$out$pred $obj ;\n" }

        raw(SPARQL_INSERT_TRANSACTION(propertiesSparql))

        return this
    }
}


class UpdateBuilder(
    private val context: TransactionContext,
    indentLevel: Int=0,
): SparqlBuilder<UpdateBuilder>(indentLevel) {
    fun insert(setup: InsertBuilder.() -> InsertBuilder): UpdateBuilder {
        if(context.numInserts++ > 0) raw(";")

        return raw("""
            insert {
                ${InsertBuilder(context, 4).setup().toString()}
            }
        """)
    }

    fun insertData(setup: InsertBuilder.() -> InsertBuilder): UpdateBuilder {
        if(context.numInserts++ > 0) raw(";")

        return raw("""
            insert data {
                ${InsertBuilder(context, 4).setup().toString()}
            }
        """)
    }

    fun where(setup: WhereBuilder.() -> WhereBuilder): UpdateBuilder {
        return raw("""
            where {
                ${WhereBuilder(context, 4).setup()}
            }
        """)
    }

    override fun toString(): String {
        return toString { this }
    }

    fun toString(config: Parameterizer.() -> Parameterizer): String {
        return parameterizedSparql(sparqlString.toString()) {
            prefixes(context.prefixes)

            literal(
                "_userId" to (context.userId?: ""),
                "_orgId" to (context.orgId?: ""),
                "_projectId" to (context.projectId?: ""),
                "_branchId" to (context.branchId?: ""),
                "_commitId" to context.commitId,
                "_transactionId" to context.transactionId,
                "_serviceId" to SERVICE_ID,
                "_requestPath" to context.requestPath,
                "_requestBody" to context.requestBody,
            )

            datatyped(
                "_now" to (Instant.now().toString() to XSDDatatype.XSDdateTime),
                "_commitMessage" to ((context.commitMessage?: "") to MMS_DATATYPE.commitMessage),
            )

            config()
        }
    }
}

class TransactionContext(
    var userId: String?=null,
    var orgId: String?=null,
    var projectId: String?=null,
    var branchId: String?=null,
    _commitId: String?=null,
    var request: ApplicationRequest,
    var commitMessage: String?=null,
    _requestBody: String?=null,
) {
    val transactionId = UUID.randomUUID().toString()
    val commitId = _commitId ?: transactionId
    val requestPath = request.path()
    val requestBody = _requestBody ?: ""

    var numInserts = 0

    val prefixes: PrefixMapBuilder
        get() = prefixesFor(
            userId = userId,
            orgId = orgId,
            projectId = projectId,
            branchId = branchId,
            commitId = commitId,
            transactionId = transactionId,
        )

    fun update(setup: UpdateBuilder.() -> UpdateBuilder): UpdateBuilder {
        return UpdateBuilder(this,).setup()
    }
}

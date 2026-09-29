package org.openmbee.flexo.mms

import org.apache.jena.atlas.io.IndentedWriter
import org.apache.jena.sparql.core.Var
import org.apache.jena.sparql.modify.request.*
import org.apache.jena.sparql.serializer.SerializationContext
import org.apache.jena.sparql.syntax.*
import org.apache.jena.update.Update
import org.apache.jena.update.UpdateRequest
import java.io.ByteArrayOutputStream

/**
 * Some quad-stores cannot accept a large SPARQL update in one request (e.g., Virtuoso's SPARQL parser rejects
 * more than ~5000 triples in one data block or VALUES table, and any request larger than 10 MB). When limits are
 * configured, a user update is split into equivalent operations of bounded size and grouped into requests of
 * bounded length:
 *
 *  - `INSERT DATA` / `DELETE DATA` blocks are split into blocks of at most [maxTriplesPerBlock] quads;
 *  - a delete-only `DELETE { … } WHERE { VALUES ?v { … } … }` whose patterns all have `?v` as their subject
 *    (the shape flexo-sysmlv2 uses to remove the triples of changed elements) is split into chunks of VALUES rows.
 *    Each row only matches and deletes triples about its own subject, so applying the chunks one after another
 *    deletes exactly what the single operation would;
 *  - all other operations are kept whole.
 *
 * Operation order is preserved.
 */
fun splitUpdateOperations(operations: List<Update>, maxTriplesPerBlock: Int): List<Update> {
    val split = mutableListOf<Update>()
    for(op in operations) {
        when(op) {
            is UpdateDataInsert -> op.quads.chunked(maxTriplesPerBlock).forEach {
                split.add(UpdateDataInsert(QuadDataAcc(it)))
            }
            is UpdateDataDelete -> op.quads.chunked(maxTriplesPerBlock).forEach {
                split.add(UpdateDataDelete(QuadDataAcc(it)))
            }
            is UpdateModify -> {
                val splittable = splittableValues(op)
                if(splittable == null || splittable.first.rows.size <= maxTriplesPerBlock) {
                    split.add(op)
                }
                else {
                    val (data, rest) = splittable
                    for(rows in data.rows.chunked(maxTriplesPerBlock)) {
                        split.add(UpdateModify().apply {
                            op.withIRI?.let { setWithIRI(it) }
                            setHasDeleteClause(true)
                            for(quad in op.deleteQuads) {
                                if(quad.isDefaultGraph) deleteAcc.addTriple(quad.asTriple())
                                else deleteAcc.addQuad(quad)
                            }
                            setElement(ElementGroup().apply {
                                addElement(ElementData(data.vars, rows))
                                rest.forEach { addElement(it) }
                            })
                        })
                    }
                }
            }
            else -> split.add(op)
        }
    }
    return split
}

// returns the VALUES block and the remaining WHERE elements if the operation can be split by VALUES rows
private fun splittableValues(op: UpdateModify): Pair<ElementData, List<Element>>? {
    if(op.hasInsertClause() || !op.hasDeleteClause()) return null
    if(op.using.isNotEmpty() || op.usingNamed.isNotEmpty()) return null
    val group = op.wherePattern as? ElementGroup ?: return null
    val data = group.elements.filterIsInstance<ElementData>().singleOrNull() ?: return null
    val variable = data.vars.singleOrNull() ?: return null
    // every row must bind the variable; an UNDEF row would match every subject
    if(data.rows.any { it.get(variable) == null }) return null
    val rest = group.elements.filter { it !== data }
    if(rest.isEmpty() || !rest.all { hasOnlySubject(it, variable) }) return null
    if(op.deleteQuads.isEmpty() || !op.deleteQuads.all { it.subject == variable }) return null
    return data to rest
}

// true if every triple pattern in the element has the given variable as its subject
private fun hasOnlySubject(element: Element, variable: Var): Boolean {
    return when(element) {
        is ElementGroup -> element.elements.isNotEmpty() && element.elements.all { hasOnlySubject(it, variable) }
        is ElementOptional -> hasOnlySubject(element.optionalElement, variable)
        is ElementPathBlock -> element.pattern.list.all { it.subject == variable }
        is ElementTriplesBlock -> element.pattern.list.all { it.subject == variable }
        else -> false
    }
}

/**
 * Serializes the given operations as one SPARQL update string, including the prologue from [sCxt].
 */
fun serializeUpdate(operations: List<Update>, sCxt: SerializationContext): String {
    val request = UpdateRequest()
    operations.forEach { request.add(it) }
    val baos = ByteArrayOutputStream()
    val out = IndentedWriter(baos)
    UpdateWriter.output(request, out, sCxt)
    out.flush()
    return baos.toString(Charsets.UTF_8)
}

/**
 * Groups the operations into as few update strings as possible without any exceeding [maxRequestBytes]
 * (an operation that is larger on its own is sent alone). With no byte limit, returns a single string.
 */
fun batchUpdateOperations(operations: List<Update>, sCxt: SerializationContext, maxRequestBytes: Long?): List<String> {
    if(maxRequestBytes == null || operations.size <= 1) return listOf(serializeUpdate(operations, sCxt))

    val prologueBytes = serializeUpdate(emptyList(), sCxt).toByteArray(Charsets.UTF_8).size
    val batches = mutableListOf<String>()
    var batch = mutableListOf<Update>()
    var batchBytes = prologueBytes.toLong()
    for(op in operations) {
        // size of the operation alone, less the prologue that each serialization repeats, plus the separator
        val opBytes = serializeUpdate(listOf(op), sCxt).toByteArray(Charsets.UTF_8).size - prologueBytes + 4L
        if(batch.isNotEmpty() && batchBytes + opBytes > maxRequestBytes) {
            batches.add(serializeUpdate(batch, sCxt))
            batch = mutableListOf()
            batchBytes = prologueBytes.toLong()
        }
        batch.add(op)
        batchBytes += opBytes
    }
    if(batch.isNotEmpty()) batches.add(serializeUpdate(batch, sCxt))
    return batches
}

/**
 * Applies a user update to [targetGraphIri] while respecting the configured request limits.
 *
 * When the update fits in one request it is applied directly, as usual. Otherwise the target graph is copied to a
 * scratch graph, the requests are applied to the scratch graph in order, and the scratch graph then replaces the
 * target graph in a single request, so a failure part-way through leaves the target graph untouched.
 */
suspend fun AnyLayer1Context.executeChunkedUserUpdate(
    sparqlUpdateAst: UpdateRequest,
    prefixMap: HashMap<String, String>,
    targetGraphIri: String,
    maxTriplesPerBlock: Int?,
    maxRequestBytes: Long?,
) {
    val direct = prepareChunkedUserUpdate(sparqlUpdateAst, prefixMap, targetGraphIri, maxTriplesPerBlock, maxRequestBytes)
    if(direct.size == 1) {
        executeSparqlUpdate(direct.first()) {this}
        return
    }

    val scratchGraphIri = "${prefixes["mor-graph"]}UpdateBuffer.$transactionId"
    val requests = prepareChunkedUserUpdate(sparqlUpdateAst, prefixMap, scratchGraphIri, maxTriplesPerBlock, maxRequestBytes)
    log("Applying user update in ${requests.size} requests via <$scratchGraphIri>")

    executeSparqlUpdate("""
        drop silent graph <$scratchGraphIri> ;
        copy silent graph <$targetGraphIri> to graph <$scratchGraphIri>
    """)
    try {
        requests.forEachIndexed { index, request ->
            log("User update request ${index + 1}/${requests.size}")
            executeSparqlUpdate(request) {this}
        }
    }
    catch(error: Throwable) {
        try {
            executeSparqlUpdate("drop silent graph <$scratchGraphIri>")
        }
        catch(cleanup: Throwable) {
            log("Failed to drop scratch graph <$scratchGraphIri>: ${cleanup.message}")
        }
        throw error
    }

    // equivalent to MOVE, spelled out so an emptied scratch graph (which some stores do not keep) still empties the target
    executeSparqlUpdate("""
        drop silent graph <$targetGraphIri> ;
        add silent graph <$scratchGraphIri> to graph <$targetGraphIri> ;
        drop silent graph <$scratchGraphIri>
    """)
}

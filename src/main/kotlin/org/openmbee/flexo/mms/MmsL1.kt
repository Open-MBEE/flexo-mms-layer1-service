package org.openmbee.flexo.mms

import com.linkedin.migz.MiGzOutputStream
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.jena.rdf.model.Property
import org.apache.jena.rdf.model.RDFNode
import org.apache.jena.rdf.model.Resource
import org.apache.jena.rdf.model.ResourceFactory
import org.apache.jena.sparql.core.Quad
import org.apache.jena.sparql.modify.request.UpdateDataDelete
import org.apache.jena.sparql.modify.request.UpdateDataInsert
import org.apache.jena.sparql.modify.request.UpdateDeleteWhere
import org.apache.jena.sparql.modify.request.UpdateModify
import org.apache.jena.update.UpdateFactory
import org.apache.jena.vocabulary.OWL
import org.apache.jena.vocabulary.RDF
import org.apache.jena.vocabulary.RDFS
import org.openmbee.flexo.mms.plugins.SparqlUpdateRequest
import org.openmbee.flexo.mms.plugins.UserDetailsPrincipal
import org.openmbee.flexo.mms.plugins.httpClient
import java.io.ByteArrayOutputStream
import java.util.*

class ParamNotParsedException(paramId: String): Exception("The {$paramId} is being used but the param was never parsed.")

val DEFAULT_BRANCH_ID = "master"

private val MIGZ_BLOCK_SIZE = 1536 * 1024


fun Resource.addNodes(vararg properties: Pair<Property, RDFNode>) {
    for(property in properties) {
        addProperty(property.first, property.second)
    }
}

fun Resource.addLiterals(vararg properties: Pair<Property, String>) {
    for(property in properties) {
        addProperty(property.first, property.second)
    }
}

fun Resource.removeNonLiterals(property: Property) {
    listProperties(property).forEach {
        if(!it.`object`.isLiteral) {
            it.remove()
        }
    }
}

val FORBIDDEN_PREDICATES_REGEX = listOf(
    RDF.uri,
    RDFS.uri,
    OWL.getURI(),
    "http://www.w3.org/ns/shacl#",
    MMS.uri,
).joinToString("|") { "^${Regex.escape(it)}" }.toRegex()

fun Quad.isSanitary(): Boolean {
    val predicateUri = this.predicate.uri
    return predicateUri.contains(FORBIDDEN_PREDICATES_REGEX)
}



fun parseConstructResponse(responseText: String, setup: RdfModeler.()->Unit): KModel {
    return RdfModeler(this, prefixes["m"]!!, responseText).apply(setup).model
}


suspend fun downloadModel(graphUri: String): KModel {
    val constructResponseText = executeSparqlConstructOrDescribe("""
        construct {
            ?s ?p ?o
        } where {
            graph ?_graph {
                ?s ?p ?o
            }
        }
    """) {
        prefixes(prefixes)

        iri(
            "_graph" to graphUri,
        )
    }

    return KModel(prefixes) {
        parseTurtle(
            body = constructResponseText,
            model = this,
        )
    }
}


// TODO: move these functions to another file

fun quadDataFilter(subjectIri: String): (Quad)->Boolean {
    return {
        it.subject.isURI && it.subject.uri == subjectIri && !it.predicate.uri.contains(FORBIDDEN_PREDICATES_REGEX)
    }
}

fun quadPatternFilter(subjectIri: String): (Quad)->Boolean {
    return {
        if(it.subject.isVariable) {
            throw VariablesNotAllowedInUpdateException("subject")
        }
        else if(!it.subject.isURI || it.subject.uri != subjectIri) {
            throw Http400Exception("All subjects must be exactly <${subjectIri}>. Refusing to evalute ${it.subject}")
        }
        else if(it.predicate.isVariable) {
            throw VariablesNotAllowedInUpdateException("predicate")
        }
        else if(it.predicate.uri.contains(FORBIDDEN_PREDICATES_REGEX)) {
            throw Http400Exception("User not allowed to set property using predicate <${it.predicate.uri}>")
        }

        true
    }
}

fun genCommitUpdate(conditions: ConditionsGroup, delete: String="", insert: String="", where: String=""): String {
    // generate sparql update
    return buildSparqlUpdate {
        delete {
            raw("""
                graph mor-graph:Metadata {
                    morb:
                        # replace branch pointer and etag
                        mms:commit ?baseCommit ;
                        mms:etag ?branchEtag ;
                        # branch will require a new model snapshot; interim lock will now point to previous one
                        mms:snapshot ?model ;
                        .
                }

                $delete
            """)
        }
        insert {
            txn(
                "mms-txn:stagingGraph" to "?stagingGraph",
                "mms-txn:baseModel" to "?model",
                "mms-txn:baseModelGraph" to "?modelGraph",
            )

            if(insert.isNotEmpty()) raw(insert)

            graph("mor-graph:Metadata") {
                raw("""
                    # new commit
                    morc: a mms:Commit ;
                        mms:etag ?_txnId ;
                        mms:parent ?baseCommit ;
                        mms:message ?_commitMessage ;
                        mms:submitted ?_now ;
                        mms:data morc-data: ;
                        mms:createdBy mu: ;
                        .
            
                    # commit data
                    morc-data: a mms:Update ;
                        mms:body ?_updateBody ;
                        mms:patch ?_patchString ;
                        mms:where ?_whereString ;
                        .
            
                    # update branch pointer and etag
                    morb: mms:commit morc: ;
                        mms:etag ?_txnId .
            
                    # convert previous snapshot to isolated lock
                    ?_interim a mms:InterimLock ;
                        mms:created ?_now ;
                        mms:commit ?baseCommit ;
                        # interim lock now points to model snapshot 
                        mms:snapshot ?model ;
                        .
                """)
            }
        }
        where {
            // `conditions` must contain the patterns that bind ?baseCommit, ?branchEtag, ?model, ?stagingGraph, and so on
            raw("""
                ${conditions.requiredPatterns().joinToString("\n")}

                $where
            """)
        }
    }
}

fun genDiffUpdate(diffTriples: String="", conditions: ConditionsGroup?=null, rawWhere: String?=null): String {
    return buildSparqlUpdate {
        insert {
            subtxn("diff", mapOf(
//                TODO: delete-below; redundant with ?srcGraph ?
//                "mms-txn:stagingGraph" to "?stagingGraph",
                "mms-txn:srcGraph" to "?srcGraph",
                "mms-txn:dstGraph" to "?dstGraph",
                "mms-txn:insGraph" to "?insGraph",
                "mms-txn:delGraph" to "?delGraph",
            )) {
                autoPolicy(Scope.DIFF, Role.ADMIN_DIFF)
            }

            raw("""
                graph ?insGraph {
                    ?ins_s ?ins_p ?ins_o .    
                }
                
                graph ?delGraph {
                    ?del_s ?del_p ?del_o .
                }
                
                graph mor-graph:Metadata {
                    ?diff a mms:Diff ;
                        mms:id ?diffId ;
                        mms:createdBy mu: ;
                        mms:srcCommit ?srcCommit ;
                        mms:dstCommit ?dstCommit ;
                        mms:insGraph ?insGraph ;
                        mms:delGraph ?delGraph ;
                        .
                }
            """)
        }
        where {
            raw("""
                ${rawWhere?: ""}
                
                bind(
                    sha256(
                        concat(str(?dstCommit), "\n", str(?srcCommit))
                    ) as ?diffId
                )
                
                bind(
                    iri(
                        concat(str(?dstCommit), "/diffs/", ?diffId)
                    ) as ?diff
                )
                
                bind(
                    iri(
                        concat(str(mor-graph:), "Diff.Ins.", ?diffId)
                    ) as ?insGraph
                )
                
                bind(
                    iri(
                        concat(str(mor-graph:), "Diff.Del.", ?diffId)
                    ) as ?delGraph
                )


                {
                    # delete every triple from the source graph...
                    graph ?srcGraph {
                        ?del_s ?del_p ?del_o .
                    }
                    
                    # ... that isn't in the destination graph 
                    filter not exists {
                        graph ?dstGraph {
                            ?del_s ?del_p ?del_o .
                        }
                    }
                } union {
                    # insert every triple from the destination graph...
                    graph ?dstGraph {
                        ?ins_s ?ins_p ?ins_o .
                    }
                    
                    # ... that isn't in the source graph
                    filter not exists {
                        graph ?srcGraph {
                            ?ins_s ?ins_p ?ins_o .
                        }
                    }
                } union {}
            """)
        }
    }
}


val COMPRESSION_TIME_BUDGET = 3 * 1000L  // algorithm is allowed up to 3 seconds max to further optimize compression
val COMPRESSION_NO_RETRY_THRESHOLD = 12 * 1024 * 1024  // do not attempt to retry if compressed output is >12 MiB
// val COMPRESSION_MIN_REDUCTION = 0.05  // each successful trail must improve compression by at least 5%

val COMPRESSION_BLOCK_SIZES = listOf(
    1536 * 1024,
    1280 * 1024,
    1792 * 1024,
    1024 * 1024,
    2048 * 1024,
    2304 * 1024,
)

fun compressStringLiteral(string: String): String? {
    // acquire bytes
    val inputBytes = string.toByteArray()

    // don't compress below 1 KiB
    if(inputBytes.size < 1024) return null

    // prep best result from compression trials
    var bestResult = ByteArray(0)
    var bestResultSize = Int.MAX_VALUE

    // initial block size
    var blockSizeIndex = 0
    var blockSize = COMPRESSION_BLOCK_SIZES[blockSizeIndex]

    // start timing
    val start = System.currentTimeMillis()

    do {
        // prep output stream
        val stream = ByteArrayOutputStream()

        // instantiate compressor
        val migz = MiGzOutputStream(stream, Runtime.getRuntime().availableProcessors(), blockSize)

        // write input data and compress
        migz.write(inputBytes)

        // acquire bytes
        val outputBytes = stream.toByteArray()

        // stop timing
        val duration = System.currentTimeMillis() - start

        // better than best result
        if(outputBytes.size < bestResultSize) {
            // replace best result
            bestResult = outputBytes
            bestResultSize = outputBytes.size

            // size exceeds retry threshold or reduction is only marginally better
            if(bestResultSize > COMPRESSION_NO_RETRY_THRESHOLD) break
        }

        // time budget exceeded
        if(duration > COMPRESSION_TIME_BUDGET) break

        // tested every block size
        if(++blockSizeIndex >= COMPRESSION_BLOCK_SIZES.size) break

        // adjust block size for next iteration
        blockSize = COMPRESSION_BLOCK_SIZES[blockSizeIndex]
    } while(true)

    return String(bestResult)
}

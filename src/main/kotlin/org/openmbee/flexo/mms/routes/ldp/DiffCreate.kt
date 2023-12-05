package org.openmbee.flexo.mms.routes.ldp

import io.ktor.http.*
import io.ktor.server.response.*
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.plugins.LdpDcLayer1Context
import org.openmbee.flexo.mms.plugins.LdpPostResponse
import java.security.MessageDigest


// default starting conditions for any calls to create a lock
private val DEFAULT_CONDITIONS = COMMIT_CRUD_CONDITIONS.append {
    // require that the user has the ability to create diffs on a repo-level scope
    permit(Permission.CREATE_DIFF, Scope.REPO)

    // require that the given diff does not exist before attempting to create it
    require("diffNotExists") {
        handler = { mms -> "The provided diff <${mms.prefixes["mord"]}> already exists." to HttpStatusCode.BadRequest }

        """
            # diff must not yet exist
            graph mor-graph:Metadata {
                filter not exists {
                    mord: a mms:Diff .
                }
            }
        """
    }
}

private fun hashString(input: String, algorithm: String): String {
    return MessageDigest
        .getInstance(algorithm)
        .digest(input.toByteArray())
        .fold("", { str, it -> str + "%02x".format(it) })
}


suspend fun LdpDcLayer1Context<LdpPostResponse>.createDiff() {
    // prepare to identify the source and destination refs for diff comparison
    lateinit var srcRef: String
    lateinit var dstRef: String

    // additional user-supplied metadata about the diff object
    var createDiffUserDataTriples = ""

    // process RDF body from user about this new diff
    filterIncomingStatements("morl") {
        // relative to this diff node
        diffNode().apply {
            // assert cardinality for src and dst refs
            srcRef = extractExactly1Uri(MMS.srcRef).uri
            dstRef = extractExactly1Uri(MMS.dstRef).uri

            // sanitize statements
            sanitizeCrudObject {
                removeAll(MMS.srcRef)
                removeAll(MMS.dstRef)
            }
        }

        // collect remaining statements about the diff object; save them to the user-supplied metadata field
        val diffPairs = serializePairs(diffNode())
        if(diffPairs.isNotEmpty()) {
            createDiffUserDataTriples = "?diff $diffPairs ."
        }

        // return the diff object as required by the filter block signature
        diffNode()
    }

    // extend the default conditions with requirements for user-specified src and dst refs
    val localConditions = DEFAULT_CONDITIONS.appendSrcRef().appendDstRef()

    // prep SPARQL UPDATE string
    val updateString = genDiffUpdate(createDiffUserDataTriples, localConditions, """
        graph mor-graph:Metadata {
            # select the commit pointed to by the source ref
            ?srcRef mms:commit ?srcCommit .
            
            # locate its corresponding snapshot and model graph
            ?srcCommit ^mms:commit/mms:snapshot ?srcSnapshot .
            ?srcSnapshot a mms:Model ; 
                mms:graph ?srcGraph  .
            
            # select the commit pointed to by the destination ref 
            ?dstRef mms:commit ?dstCommit .
            
            # locate its corresponding snapshot and model graph
            ?dstCommit ^mms:commit/mms:snapshot ?dstSnapshot .
            ?dstSnapshot a mms:Model ; 
                mms:graph ?dstGraph .
        }
    """)

    // execute update
    executeSparqlUpdate(updateString) {
        prefixes(prefixes)

        // replace IRI substitution variables
        iri(
            "srcRef" to srcRef,
            "dstRef" to dstRef,
        )
    }

    // create construct query to confirm transaction and fetch diff details
    val constructString = buildSparqlQuery {
        construct {
            // all the details about this transaction (provide sub txn id to distinguish from others)
            txn("diff")

            // all the properties about this diff
            raw("""
                mord: ?mord_p ?mord_o .
            """)
        }
        where {
            // first group in a series of unions fetches intended outputs
            group {
                txn("diff", "mord")

                raw("""
                    graph mor-graph:Metadata {
                        mord: ?mord_p ?mord_o .
                    }
                """)
            }
            // all subsequent unions are for inspecting what if any conditions failed
            raw("""union ${localConditions.unionInspectPatterns()}""")
        }
    }

    // execute construct
    val constructResponseText = executeSparqlConstructOrDescribe(constructString)

    // validate whether the transaction succeeded
    val constructModel = validateTransaction(constructResponseText, localConditions, "diff", "mord")

    // check that the user-supplied HTTP preconditions were met
    handleEtagAndPreconditions(constructModel, prefixes["mord"])

    // respond
    call.respondText(constructResponseText, RdfContentTypes.Turtle)

    // delete transaction
    run {
        // submit update
        val dropResponseText = executeSparqlUpdate("""
            delete where {
                graph m-graph:Transactions {
                    mt: ?p ?o .
                }
            }
        """)

        // log response
        log.info(dropResponseText)
    }
}

package org.openmbee.flexo.mms.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.server.LdpDcLayer1Context
import org.openmbee.flexo.mms.server.LdpPostResponse
import org.openmbee.flexo.mms.server.linkedDataPlatformDirectContainer


// conditions for squash: repo must exist, user needs UPDATE_COMMIT at repo scope.
// Only one permit() call is used because permittedActionSparqlBgp() generates SPARQL patterns
// with shared variable names (?__mms_policy, ?__mms_scope, etc.) — multiple permit() calls in
// the same ConditionsGroup would cause variable collisions in the WHERE clause. UPDATE_LOCK is
// not needed here because locks are only read (to resolve commits), not modified.
private val SQUASH_CONDITIONS = REPO_CRUD_CONDITIONS.append {
    permit(Permission.UPDATE_COMMIT, Scope.REPO)
}

private const val SQUASH_PATH = "/orgs/{orgId}/repos/{repoId}/squash"

/**
 * Squash Commits routing
 *
 * POST /orgs/{orgId}/repos/{repoId}/squash
 *
 * Accepts a Turtle RDF body with two lock references (mms:srcRef and mms:dstRef),
 * verifies a linear commit path between the two locks' commits,
 * removes intermediate commits, and replaces the newer lock's commit data patch
 * with a single squashed diff computed from the two locks' model graphs.
 */
fun Route.squashCommits() {
    linkedDataPlatformDirectContainer(SQUASH_PATH) {
        beforeEach = {
            parsePathParams {
                org()
                repo()
            }
        }

        post { _ ->
            squashCommitsImpl()
        }
    }
}


suspend fun LdpDcLayer1Context<LdpPostResponse>.squashCommitsImpl() {
    // parse the two lock references from the RDF body
    lateinit var srcLockRef: String
    lateinit var dstLockRef: String

    filterIncomingStatements("mor") {
        repoNode().apply {
            srcLockRef = extractExactly1Uri(MMS.srcRef).uri
            dstLockRef = extractExactly1Uri(MMS.dstRef).uri
        }
    }

    // ===== Step 1: Resolve both locks and validate they exist =====
    val resolveLockQuery = buildSparqlQuery {
        construct {
            raw("""
                ?lock1 mms:commit ?commit1 ; mms:snapshot ?snapshot1 .
                ?snapshot1 mms:graph ?graph1 .
                ?lock2 mms:commit ?commit2 ; mms:snapshot ?snapshot2 .
                ?snapshot2 mms:graph ?graph2 .
            """)
        }
        where {
            raw("""
                graph mor-graph:Metadata {
                    ?lock1 a mms:Lock ; mms:commit ?commit1 ; mms:snapshot ?snapshot1 .
                    ?snapshot1 mms:graph ?graph1 .
                    ?lock2 a mms:Lock ; mms:commit ?commit2 ; mms:snapshot ?snapshot2 .
                    ?snapshot2 mms:graph ?graph2 .
                }
            """)
        }
    }

    val resolveResponseText = executeSparqlConstructOrDescribe(resolveLockQuery) {
        prefixes(prefixes)
        iri(
            "lock1" to srcLockRef,
            "lock2" to dstLockRef,
        )
    }

    // parse the result and extract commit/graph IRIs
    val resolveModel = KModel().apply {
        parseTurtle(resolveResponseText, this)
    }

    val srcLockRes = resolveModel.createResource(srcLockRef)
    val dstLockRes = resolveModel.createResource(dstLockRef)

    // validate both locks exist
    if (!srcLockRes.listProperties().hasNext()) {
        throw Http404Exception("Source lock <$srcLockRef>")
    }
    if (!dstLockRes.listProperties().hasNext()) {
        throw Http404Exception("Destination lock <$dstLockRef>")
    }

    val srcCommitIri = resolveModel.listObjectsOfProperty(srcLockRes, MMS.commit).next().asResource().uri
    val dstCommitIri = resolveModel.listObjectsOfProperty(dstLockRes, MMS.commit).next().asResource().uri

    val srcSnapshotRes = resolveModel.listObjectsOfProperty(srcLockRes, MMS.snapshot).next().asResource()
    val dstSnapshotRes = resolveModel.listObjectsOfProperty(dstLockRes, MMS.snapshot).next().asResource()

    val srcGraphIri = resolveModel.listObjectsOfProperty(srcSnapshotRes, MMS.graph).next().asResource().uri
    val dstGraphIri = resolveModel.listObjectsOfProperty(dstSnapshotRes, MMS.graph).next().asResource().uri

    // ===== Step 2: Determine which commit is newer and verify linear path =====
    if (srcCommitIri == dstCommitIri) {
        throw Http400Exception("Both locks point to the same commit. Nothing to squash.")
    }

    // Check if dstCommit is a descendant of srcCommit (dst is newer)
    val askDstNewerQuery = buildSparqlQuery {
        ask {
            raw("""
                graph mor-graph:Metadata {
                    ?dstCommit mms:parent+ ?srcCommit .
                }
            """)
        }
    }
    val askDstNewerResult = executeSparqlSelectOrAsk(askDstNewerQuery) {
        prefixes(prefixes)
        iri(
            "srcCommit" to srcCommitIri,
            "dstCommit" to dstCommitIri,
        )
    }
    val dstIsNewer = parseSparqlResultsJsonAsk(askDstNewerResult)

    // Check if srcCommit is a descendant of dstCommit (src is newer)
    val askSrcNewerQuery = buildSparqlQuery {
        ask {
            raw("""
                graph mor-graph:Metadata {
                    ?srcCommit mms:parent+ ?dstCommit .
                }
            """)
        }
    }
    val askSrcNewerResult = executeSparqlSelectOrAsk(askSrcNewerQuery) {
        prefixes(prefixes)
        iri(
            "srcCommit" to srcCommitIri,
            "dstCommit" to dstCommitIri,
        )
    }
    val srcIsNewer = parseSparqlResultsJsonAsk(askSrcNewerResult)

    if (!dstIsNewer && !srcIsNewer) {
        throw Http400Exception("No linear commit path between the two locks")
    }

    // determine older/newer
    val olderCommitIri: String
    val newerCommitIri: String
    val olderGraphIri: String
    val newerGraphIri: String
    if (dstIsNewer) {
        olderCommitIri = srcCommitIri
        newerCommitIri = dstCommitIri
        olderGraphIri = srcGraphIri
        newerGraphIri = dstGraphIri
    } else {
        olderCommitIri = dstCommitIri
        newerCommitIri = srcCommitIri
        olderGraphIri = dstGraphIri
        newerGraphIri = srcGraphIri
    }

    // ===== Step 3: Compute the diff between the two model graphs =====
    val diffInsGraphIri = "${prefixes["mor-graph"]}Squash.Ins.${transactionId}"
    val diffDelGraphIri = "${prefixes["mor-graph"]}Squash.Del.${transactionId}"

    val computeDiffUpdate = """
        insert {
            graph <$diffInsGraphIri> {
                ?ins_s ?ins_p ?ins_o .
            }
            graph <$diffDelGraphIri> {
                ?del_s ?del_p ?del_o .
            }
        }
        where {
            {
                # triples in newer graph but not in older graph (insertions)
                graph ?newerGraph {
                    ?ins_s ?ins_p ?ins_o .
                }
                filter not exists {
                    graph ?olderGraph {
                        ?ins_s ?ins_p ?ins_o .
                    }
                }
            } union {
                # triples in older graph but not in newer graph (deletions)
                graph ?olderGraph {
                    ?del_s ?del_p ?del_o .
                }
                filter not exists {
                    graph ?newerGraph {
                        ?del_s ?del_p ?del_o .
                    }
                }
            } union {}
        }
    """.trimIndent()

    executeSparqlUpdate(computeDiffUpdate) {
        prefixes(prefixes)
        iri(
            "olderGraph" to olderGraphIri,
            "newerGraph" to newerGraphIri,
        )
    }

    // Wrap remaining steps in try/finally so diff graphs are cleaned up on failure.
    // On success, the diff graphs become the newer commit's mms:insGraph/mms:delGraph
    // and must NOT be dropped.
    var squashSucceeded = false
    try {

    // ===== Step 4: Build the squashed patch string =====
    val patchString = """
        delete {
            graph ?__mms_model {
                ?s ?p ?o .
            }
        } where {
            graph <$diffDelGraphIri> {
                ?s ?p ?o .
            }
        };
        insert {
            graph ?__mms_model {
                ?s ?p ?o .
            }
        } where {
            graph <$diffInsGraphIri> {
                ?s ?p ?o .
            }
        }
    """.trimIndent()

    // ===== Step 5: Collect intermediate commits =====
    val intermediateQuery = """
        select ?intermediateCommit ?intermediateData where {
            graph mor-graph:Metadata {
                ?newerCommit mms:parent+ ?intermediateCommit .
                ?intermediateCommit mms:parent+ ?olderCommit ;
                    mms:data ?intermediateData .
            }
        }
    """.trimIndent()

    val intermediateResult = executeSparqlSelectOrAsk(intermediateQuery) {
        prefixes(prefixes)
        iri(
            "newerCommit" to newerCommitIri,
            "olderCommit" to olderCommitIri,
        )
    }

    val intermediateBindings = parseSparqlResultsJsonSelect(intermediateResult)

    // collect intermediate commit and data IRIs
    val intermediateCommitIris = intermediateBindings.map {
        it["intermediateCommit"]!!.jsonObject["value"]!!.jsonPrimitive.content
    }
    val intermediateDataIris = intermediateBindings.map {
        it["intermediateData"]!!.jsonObject["value"]!!.jsonPrimitive.content
    }

    // ===== Step 5b: Verify no intermediate commit is a branch point =====
    // If any intermediate commit is the parent of a commit outside the squash path
    // (i.e. not the newer commit and not another intermediate), deleting it would
    // orphan that external branch's commit history.
    if (intermediateCommitIris.isNotEmpty()) {
        // all commits in the squash path: newer + intermediates
        val squashPathCommits = listOf(newerCommitIri) + intermediateCommitIris
        val squashPathFilter = squashPathCommits.joinToString(", ") { "<$it>" }

        val branchPointQuery = """
            select ?intermediateCommit ?externalChild where {
                graph mor-graph:Metadata {
                    ?externalChild mms:parent ?intermediateCommit .
                    filter(?intermediateCommit in ($squashPathFilter))
                    filter(?externalChild not in ($squashPathFilter))
                }
            }
        """.trimIndent()

        val branchPointResult = executeSparqlSelectOrAsk(branchPointQuery) {
            prefixes(prefixes)
        }
        val branchPointBindings = parseSparqlResultsJsonSelect(branchPointResult)
        if (branchPointBindings.isNotEmpty()) {
            val branchPointIri = branchPointBindings[0]["intermediateCommit"]!!.jsonObject["value"]!!.jsonPrimitive.content
            throw Http400Exception(
                "Cannot squash: intermediate commit <$branchPointIri> is a branch point " +
                "(parent of commits outside the squash path). Squashing would destroy their commit history."
            )
        }
    }

    // also collect ins/del graphs from intermediate commit data for cleanup
    // use OPTIONAL for insGraph/delGraph since a commit's data may only have one or neither
    val intermediateInsDelQuery = """
        select ?data ?insGraph ?delGraph where {
            graph mor-graph:Metadata {
                filter(?data in (${intermediateDataIris.joinToString(", ") { "<$it>" }}))
                optional { ?data mms:insGraph ?insGraph . }
                optional { ?data mms:delGraph ?delGraph . }
            }
        }
    """.trimIndent()

    var intermediateGraphsToClean = listOf<String>()
    if (intermediateDataIris.isNotEmpty()) {
        val insDelResult = executeSparqlSelectOrAsk(intermediateInsDelQuery) {
            prefixes(prefixes)
        }
        val insDelBindings = parseSparqlResultsJsonSelect(insDelResult)
        intermediateGraphsToClean = insDelBindings.flatMap { binding ->
            listOfNotNull(
                binding["insGraph"]?.jsonObject?.get("value")?.jsonPrimitive?.content,
                binding["delGraph"]?.jsonObject?.get("value")?.jsonPrimitive?.content,
            )
        }
    }

    // also get the newer commit's current data, parent, and its ins/del graphs for replacement
    val newerCommitInfoQuery = """
        select ?newerData ?oldParent ?oldPatch ?oldInsGraph ?oldDelGraph where {
            graph mor-graph:Metadata {
                ?newerCommit mms:data ?newerData ;
                             mms:parent ?oldParent .
                ?newerData mms:patch ?oldPatch .
                optional { ?newerData mms:insGraph ?oldInsGraph . }
                optional { ?newerData mms:delGraph ?oldDelGraph . }
            }
        }
    """.trimIndent()

    val newerCommitInfoResult = executeSparqlSelectOrAsk(newerCommitInfoQuery) {
        prefixes(prefixes)
        iri(
            "newerCommit" to newerCommitIri,
        )
    }

    val newerCommitInfoBindings = parseSparqlResultsJsonSelect(newerCommitInfoResult)
    if (newerCommitInfoBindings.isEmpty()) {
        throw Http500Excpetion("Failed to retrieve newer commit data")
    }

    val newerDataIri = newerCommitInfoBindings[0]["newerData"]!!.jsonObject["value"]!!.jsonPrimitive.content
    val oldParentIri = newerCommitInfoBindings[0]["oldParent"]!!.jsonObject["value"]!!.jsonPrimitive.content
    val oldInsGraphIri = newerCommitInfoBindings[0]["oldInsGraph"]?.jsonObject?.get("value")?.jsonPrimitive?.content
    val oldDelGraphIri = newerCommitInfoBindings[0]["oldDelGraph"]?.jsonObject?.get("value")?.jsonPrimitive?.content

    // ===== Step 6: Execute the squash as a single SPARQL UPDATE =====
    // All mutations are consolidated into one DELETE/INSERT/WHERE block so that
    // authorization conditions gate everything atomically.
    val localConditions = SQUASH_CONDITIONS

    // Build delete clauses for intermediate commits
    val intermediateDeletePatterns = if (intermediateCommitIris.isNotEmpty()) {
        val commitPatterns = intermediateCommitIris.mapIndexed { idx, iri ->
            "<$iri> ?ic_p_$idx ?ic_o_$idx ."
        }
        val dataPatterns = intermediateDataIris.mapIndexed { idx, iri ->
            "<$iri> ?id_p_$idx ?id_o_$idx ."
        }
        (commitPatterns + dataPatterns).joinToString("\n                    ")
    } else ""

    // Build delete clauses for old patch data
    val deleteOldDataClauses = mutableListOf(
        "<$newerDataIri> mms:patch ?oldPatch ."
    )
    if (oldInsGraphIri != null) {
        deleteOldDataClauses.add("<$newerDataIri> mms:insGraph <$oldInsGraphIri> .")
    }
    if (oldDelGraphIri != null) {
        deleteOldDataClauses.add("<$newerDataIri> mms:delGraph <$oldDelGraphIri> .")
    }

    val squashUpdateString = buildSparqlUpdate {
        delete {
            graph("mor-graph:Metadata") {
                // delete intermediate commits and their data
                if (intermediateDeletePatterns.isNotEmpty()) {
                    raw(intermediateDeletePatterns)
                }

                // delete old parent pointer
                raw("""
                    <$newerCommitIri> mms:parent <$oldParentIri> .
                """)

                // delete old patch/insGraph/delGraph
                raw(deleteOldDataClauses.joinToString("\n                    "))
            }
        }
        insert {
            // record the transaction for validation
            subtxn("squash")

            graph("mor-graph:Metadata") {
                // new parent pointer
                raw("""
                    <$newerCommitIri> mms:parent <$olderCommitIri> .
                """)

                // new squashed patch and diff graphs
                raw("""
                    <$newerDataIri> mms:patch ?_squashedPatch .
                    <$newerDataIri> mms:insGraph <$diffInsGraphIri> .
                    <$newerDataIri> mms:delGraph <$diffDelGraphIri> .
                """)
            }
        }
        where {
            // enforce authorization conditions (org/repo existence, UPDATE_COMMIT)
            raw(*localConditions.requiredPatterns())

            // match intermediate commit triples for deletion
            if (intermediateDeletePatterns.isNotEmpty()) {
                graph("mor-graph:Metadata") {
                    raw(intermediateDeletePatterns)
                }
            }

            // match old patch data for deletion
            graph("mor-graph:Metadata") {
                raw("""
                    <$newerDataIri> mms:patch ?oldPatch .
                """)
            }
        }
    }

    executeSparqlUpdate(squashUpdateString) {
        prefixes(prefixes)
        datatyped(
            "_squashedPatch" to (patchString to MMS_DATATYPE.sparql),
        )
        iri(
            "_requiredPermission" to "${prefixes["mms-object"]}Permission.${Permission.UPDATE_COMMIT.id}",
        )
    }

    // ===== Verify transaction and respond =====
    val constructString = buildSparqlQuery {
        construct {
            txn("squash")

            raw("""
                <$newerCommitIri> ?commit_p ?commit_o .
                ?newerData ?data_p ?data_o .
            """)
        }
        where {
            group {
                txn("squash")

                raw("""
                    graph mor-graph:Metadata {
                        <$newerCommitIri> ?commit_p ?commit_o ;
                            mms:data ?newerData .
                        ?newerData ?data_p ?data_o .
                    }
                """)
            }
            // inspect conditions for diagnostics if transaction failed
            raw("""union ${localConditions.unionInspectPatterns()}""")
        }
    }

    // execute construct
    val constructResponseText = executeSparqlConstructOrDescribe(constructString)

    // validate whether the transaction succeeded (checks conditions passed)
    validateTransaction(constructResponseText, localConditions, "squash")

    // mark success immediately after validation — the squash update is now confirmed
    // in the triplestore, so diff graphs must be preserved even if response/cleanup fails
    squashSucceeded = true

    // ===== Step 7: Clean up old ins/del graphs from intermediate commits and old newer commit data =====
    // This runs AFTER validateTransaction to avoid dropping graphs that are still
    // referenced by metadata if the squash UPDATE was a no-op (e.g. auth failure).
    val graphsToClean = mutableListOf<String>()
    graphsToClean.addAll(intermediateGraphsToClean)
    // also drop old ins/del graphs from the newer commit's previous data if they differ from new ones
    if (oldInsGraphIri != null && oldInsGraphIri != diffInsGraphIri) {
        graphsToClean.add(oldInsGraphIri)
    }
    if (oldDelGraphIri != null && oldDelGraphIri != diffDelGraphIri) {
        graphsToClean.add(oldDelGraphIri)
    }

    if (graphsToClean.isNotEmpty()) {
        val dropStatements = graphsToClean.joinToString(";\n") { "drop silent graph <$it>" }
        executeSparqlUpdate(dropStatements) {
            prefixes(prefixes)
        }
    }

    // respond
    call.response.header(HttpHeaders.ETag, transactionId)
    call.respondText(constructResponseText, RdfContentTypes.Turtle, HttpStatusCode.OK)

    // clean up transaction
    run {
        val dropResponseText = executeSparqlUpdate("""
            delete where {
                graph m-graph:Transactions {
                    mt: ?p ?o .
                }
            }
        """)
        log.info(dropResponseText)
    }

    } finally {
        // only clean up temporary diff graphs on failure — on success they are
        // stored as the newer commit's mms:insGraph/mms:delGraph
        if (!squashSucceeded) {
            executeSparqlUpdate("""
                drop silent graph <$diffInsGraphIri> ;
                drop silent graph <$diffDelGraphIri>
            """) {
                prefixes(prefixes)
            }
        }
    }
}

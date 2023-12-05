package org.openmbee.flexo.mms

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.apache.jena.rdf.model.Resource
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.plugins.*
import java.util.*

/**
 * In some of the hardcoded SPARQL strings, a directive following the form `# @values {PARAM_ID}` is used to indicate
 * a part of the SPARQL that must be replaced by a whitespace-delimited list of resources inside a VALUES block.
 *
 * This function performs that replacement given the input SPARQL string and parameter id to value mapping pairs.
 */
private fun replaceValuesDirectives(sparql: String, vararg pairs: Pair<String, List<String>>): String {
    var replaced = sparql
    for(pair in pairs) {
        replaced = replaced.replace("""\n\s*#+\s*@values\s+${pair.first}\s*\n""".toRegex(), pair.second.joinToString(" ") { escapeLiteral(it) })
    }
    return replaced
}

typealias AnyLayer1Context = Layer1Context<*, *>

class Layer1Context<TRequestContext: GenericRequest, TResponseContext: GenericResponse>(
    val requestContext: TRequestContext,
    val responseContext: TResponseContext,
) {
    val call
        get() = responseContext.call

    // logger
    val log = call.application.log

    // unique transaction ID associated with this request
    val transactionId = UUID.randomUUID().toString()

    // auth session data
    val session = call.principal<UserDetailsPrincipal>()

    // user and group extracted from auth data
    val userId: String
    val groups: List<String>

    // etag qualifications
    val ifMatch = call.parseEtagQualifierHeader(HttpHeaders.IfMatch)
    val ifNoneMatch = call.parseEtagQualifierHeader(HttpHeaders.IfNoneMatch)

    /*
     * All of the following identify the resource being CRUD'd -- has nothing to do with the current user
     */
    var groupId: String? = null
    var orgId: String? = null
    var collectionId: String? = null
    var repoId: String? = null
    var commitId: String = transactionId
    var lockId: String? = null
    var branchId: String? = null
    var diffId: String? = null
    var policyId: String? = null

    val prefixes: PrefixMapBuilder
        get() = prefixesFor(
            userId = userId,
            groupId = groupId,
            orgId = orgId,
            collectionId = collectionId,
            repoId = repoId,
            branchId = branchId,
            commitId = commitId,
            lockId = lockId,
            diffId = diffId,
            transactionId = transactionId,
            policyId = policyId,
        )

    var inspectOnly: Boolean = false

    var refSource: String? = null
    var commitSource: String? = null

    var commitMessage: String? = null

    val requestPath = call.request.path()
    val requestMethod = call.request.httpMethod.value
    val requestBodyContentType = call.request.contentType().toString()

    val defaultHttpClient = call.httpClient()

    /**
     * Allows caller to specify which path params are expected
     */
    inner class PathParamNormalizer() {
        fun group(legal: Boolean=false) {
            groupId = call.parameters["groupId"]?: throw Http400Exception("Requisite {groupId} parameter was null")
            if(legal) assertLegalId(groupId!!, """[/?&=.,_\pL-]{3,256}""".toRegex())
        }

        fun org(legal: Boolean=false) {
            orgId = call.parameters["orgId"]
            if(legal) assertLegalId(orgId!!)
        }

        fun collection(legal: Boolean=false) {
            collectionId = call.parameters["collectionId"]
            if(legal) assertLegalId(collectionId!!)
        }

        fun repo(legal: Boolean=false) {
            repoId = call.parameters["repoId"]
            if(legal) assertLegalId(repoId!!)
        }

        fun commit(legal: Boolean=false) {
            commitId = call.parameters["commitId"]?: throw Http400Exception("Requisite {commitId} parameter was null")
            if(legal) assertLegalId(commitId)
        }

        fun lock(legal: Boolean=false) {
            lockId = call.parameters["lockId"]
            if(legal) assertLegalId(lockId!!)
        }

        fun branch(legal: Boolean=false) {
            branchId = call.parameters["branchId"]
            if(legal) assertLegalId(branchId!!)
        }

        fun diff() {
            diffId = call.parameters["diffId"]
        }

        fun policy(legal: Boolean=false) {
            policyId = call.parameters["policyId"]
            if(legal) assertLegalId(policyId!!)
        }

        fun inspect() {
            val inspectValue = call.parameters["inspect"]?: ""
            inspectOnly = if(inspectValue.isNotEmpty()) {
                if(inspectValue != "inspect") {
                    throw Http404Exception(call.request.path())
                } else true
            } else false
        }
    }

    /*
     * Initialize the context, asserting the auth session data is valid.
     */
    init {
        // log a summary of the groups this user belongs to
        val groupsSummary = session?.groups?.joinToString(",") { "<$it>" }?: "none"
        log("${call.request.httpMethod.value} ${call.request.path()} @${session?.name?: "{anonymous}"} in (${groupsSummary})")

        // missing userId
        if((session == null) || session.name.isBlank()) {
            throw AuthorizationRequiredException("User not authenticated")
        }

        // destructure parts of session for convenient access
        userId = session.name
        groups = session.groups

        // forbid the use of `If-Match` and `If-None-Match` headers in POST operations
        if(call.request.httpMethod == HttpMethod.Post && (call.request.headers.contains("If-Match") || call.request.headers.contains("If-None-Match"))) {
            throw PreconditionsForbidden("when creating a resource")
        }
    }

    /**
     * Wrapper function adds transaction id context when writing to logger
     */
    fun log(message: String) {
        log.debug("txn/${transactionId}: $message")
    }


    /**
     * Validates path parameters and saves their values to the corresponding field on this instance
     */
    fun parsePathParams(setup: PathParamNormalizer.()->Unit): PathParamNormalizer {
        return PathParamNormalizer().apply{
            setup()
        }
    }

    /**
     * Build a SPARQL Query string using a DSL
     */
    fun buildSparqlQuery(setup: QueryBuilder.() -> Unit): String {
        return QueryBuilder(this).apply { setup() }.toString()
    }

    /**
     * Build a SPARQL Update string using a DSL
     */
    fun buildSparqlUpdate(setup: UpdateBuilder.() -> Unit): String {
        return UpdateBuilder(this).apply { setup() }.toString()
    }

    /**
     * Execute a SPARQL Query string against Layer 0
     */
    @OptIn(InternalAPI::class)
    suspend fun executeSparqlQuery(pattern: String, acceptType: ContentType, setup: (SparqlParameterizer.() -> Unit)?=null): String {
        // apply the optional parameterizer setup, default to using the built-in prefixes
        val params = SparqlParameterizer(pattern).apply {
            if(setup != null) setup()
            else prefixes(prefixes)
        }

        // stringify the SPARQL query
        var sparql = params.toString()

        // apply global replacement rules
        sparql = replaceValuesDirectives(sparql,
            "groupId" to groups,
        )

        // if the query caller accepts replica lag, use the more optimal query URL; otherwise use master
        val endpoint = if(params.acceptReplicaLag) call.application.quadStoreQueryUrl
            else call.application.quadStoreMasterQueryUrl

        log("Executing SPARQL Query to $endpoint:\n$sparql")
        // submit the query to the appropriate endpoint and handle the response
        return handleSparqlResponse(defaultHttpClient.post(endpoint) {
            headers {
                append(HttpHeaders.Accept, acceptType)
            }
            contentType(RdfContentTypes.SparqlQuery)
            body=sparql
        })
    }

    /**
     * Execute a SPARQL SELECT or ASK Query
     */
    suspend fun executeSparqlSelectOrAsk(pattern: String, setup: (SparqlParameterizer.() -> Unit)?=null): String {
        return executeSparqlQuery(pattern, RdfContentTypes.SparqlResultsJson, setup)
    }

    /**
     * Execute a SPARQL CONSTRUCT or DESCRIBE Query
     */
    suspend fun executeSparqlConstructOrDescribe(pattern: String, setup: (SparqlParameterizer.() -> Unit)?=null): String {
        return executeSparqlQuery(pattern, RdfContentTypes.Turtle, setup)
    }

    /**
     * Execute a SPARQL Update string against Layer 0
     */
    @OptIn(InternalAPI::class)
    suspend fun executeSparqlUpdate(pattern: String, setup: (SparqlParameterizer.() -> SparqlParameterizer)?=null): String {
        var sparql = SparqlParameterizer(pattern.trimIndent()).apply {
            if(setup != null) setup()
            prefixes(prefixes)
        }.toString()

        sparql = replaceValuesDirectives(sparql,
            "groupId" to groups,
        )

        log("Executing SPARQL Update:\n$sparql")

        return handleSparqlResponse(defaultHttpClient.post(call.application.quadStoreUpdateUrl) {
            headers {
                // no expectation on response content type
                append(HttpHeaders.Accept, ContentType.Any)
            }
            contentType(RdfContentTypes.SparqlUpdate)
            body=sparql
        })
    }


    private fun checkPreconditions(etag: String) {
        // preconditions not be used in POST operations; no-op
        if(call.request.httpMethod == HttpMethod.Post) return

        // check If-Match preconditions
        if(ifMatch != null && !ifMatch.isStar) {
            // precondition failed
            if(!ifMatch.etags.contains(etag)) {
                throw PreconditionFailedException("If-Match")
            }
        }

        // check If-None-Match preconditions
        if(ifNoneMatch != null) {
            // precondition is that resource does not exist
            if(ifNoneMatch.isStar) {
                // but the resource does exist; 304
                throw NotModifiedException()
            }
            // precondition failed
            else if(ifNoneMatch.etags.contains(etag)) {
                throw PreconditionFailedException("If-None-Match")
            }
        }
    }

    fun handleEtagAndPreconditions(bindings: List<JsonObject>) {
        // resource does not exist
        if(bindings.isEmpty()) {
            throw Http404Exception(call.request.path())
        }

        // compose etag
        val etag = if(bindings.size == 1) {
            bindings[0]["__mms_etag"]!!.jsonObject["value"]!!.jsonPrimitive.content
        }  else {
            bindings.joinToString(":") { it["__mms_etag"]!!.jsonObject["value"]!!.jsonPrimitive.content }.sha256()
        }

        // set etag value in response header
        call.response.header(HttpHeaders.ETag, etag)

        // check preconditions
        checkPreconditions(etag)
    }

    @JvmOverloads
    fun handleEtagAndPreconditions(model: KModel, resourceUri: String?, allEtags: Boolean?=false) {
        // single resource
        if(resourceUri != null) {
            // create resource node in model
            val resourceNode = model.createResource(resourceUri)

            // resource not exists; 404
            if(!resourceNode.listProperties().hasNext()) {
                throw Http404Exception(call.request.path())
            }

            // etags
            val etags = model.listObjectsOfProperty(if(true == allEtags) null else resourceNode, MMS.etag).toList()
            val etag = when(etags.size) {
                0 -> throw ServerBugException("Constructed model did not contain any etag values.")
                1 -> etags[0].asLiteral().string
                else -> etags.map { it.asLiteral().string }.sorted()
                    .joinToString(":").sha256()
            }

            // set etag value in response header
            call.response.header(HttpHeaders.ETag, etag)

            // check preconditions
            checkPreconditions(etag)
        }
    }

    fun handleEtagAndPreconditions(model: KModel, resourceType: Resource) {
        // empty model; user does not have permissions to enumerate
        if(model.size() == 0L) {
            throw Http403Exception(this)
        }

        // prep map of resources to etags
        val resEtags = mutableListOf<String>()

        // each node that has an etag...
        model.listSubjectsWithProperty(MMS.etag).forEach { subject ->
            // ...that is of the specified type
            if(subject.hasProperty(RDF.type, resourceType)) {
                // add all etgas
                resEtags.addAll(subject.listProperties(MMS.etag).mapWith { statement ->
                    statement.`object`.asLiteral().string
                }.toList())
            }
        }

        // sort list of etags and hash them
        val etag = resEtags.sorted().toList().joinToString(":").sha256()

        // set etag value in response header
        call.response.header(HttpHeaders.ETag, etag)

        // check preconditions
        checkPreconditions(etag)
    }

    /**
     * Process RDF body from user request, for normalizing content and sanitizing inputs.
     */
    suspend fun filterIncomingStatements(basePrefixId: String, setup: RdfModeler.()->Resource): String {
        val baseIri = prefixes[basePrefixId]?: throw ParamNotParsedException(basePrefixId)
        val content = "${prefixes}\n${call.receiveText()}"

        return KModel(prefixes) {
            add(RdfModeler(this@Layer1Context, baseIri, content).setup().listProperties())
        }.stringify(emitPrefixes=false)
    }


    fun validateTransaction(results: String, conditions: ConditionsGroup, subTxnId: String?=null, scope: String?=null): KModel {
        return parseConstructResponse(results) {
            // transaction failed
            if(!transactionNode(subTxnId).listProperties().hasNext()) {
                runBlocking {
                    val constructQuery = buildSparqlQuery {
                        construct {
                            raw("""
                                ?__mms_policy ?__mms_policy_p ?__mms_policy_o . 
                                
                                mt: ?mt_p ?mt_o .
                                
                                ${if(repoId != null) {
                                    """
                                        # outgoing repo properties
                                        mor: ?mor_p ?mor_o .
                                    
                                        # properties of things that belong to this repo
                                        ?thing ?thing_p ?thing_o .
                                    
                                        # all triples in metadata graph
                                        ?m_s ?m_p ?m_o .
                                    """
                                } else ""}
                            """)
                        }
                        where {
                            raw("""
                                {
                                    optional {
                                        graph m-graph:AccessControl.Policies {
                                            ?__mms_policy mms:scope ${scope?: "mo"}: ;
                                                ?__mms_policy_p ?__mms_policy_o .
                                        }
                                    }
                                } union {
                                    graph m-graph:Transactions {
                                        mt: ?mt_p ?mt_o .
                                    }
                                } 
                                ${if(repoId != null) {
                                    """
                                        union {
                                            graph m-graph:Cluster {
                                                mor: a mms:Repo ;
                                                    ?mor_p ?mor_o .
            
                                                optional {
                                                    ?thing mms:repo mor: ;
                                                        ?thing_p ?thing_o .
                                                }
                                            }
                                        } union {
                                            graph mor-graph:Metadata {
                                                ?m_s ?m_p ?m_o .
                                            }
                                        }
                                    """
                                } else ""}
                            """)
                        }
                    }

                    val described = executeSparqlConstructOrDescribe(constructQuery)
                    log("Transaction failed.\n\n\tInpsect: ${described}\n\n\tResults: \n${results}")
                }

                // use response to diagnose cause
                conditions.handle(model, layer1);

                // the above always throws, so this is unreachable
            }
        }
    }


}
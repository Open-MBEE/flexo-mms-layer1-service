package org.openmbee.flexo.mms

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.jena.rdf.model.Resource
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.server.GenericRequest
import org.openmbee.flexo.mms.server.GenericResponse
import org.openmbee.flexo.mms.server.UserDetailsPrincipal
import org.openmbee.flexo.mms.server.httpClient
import java.util.*

val DEFAULT_BRANCH_ID = "master"


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

/**
 * Convenience type for any context
 */
typealias AnyLayer1Context = Layer1Context<*, *>

/**
 * Encapsulates both the request and response contexts, providing properties and methods relevant to any route handler
 */
class Layer1Context<TRequestContext: GenericRequest, out TResponseContext: GenericResponse>(
    val requestContext: TRequestContext,
    val responseContext: TResponseContext,
) {
    // effectively creates an overloaded constructor that allows TResponseContext to be inferred from the default value `GenericResponse(requestContext)`
    companion object {
        operator fun <TRequestContext: GenericRequest> invoke(requestContext: TRequestContext) = Layer1Context(requestContext, GenericResponse(requestContext))
    }

    // get the call value from the request context
    val call
        get() = requestContext.call

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

    // precondition implications
    val isPostMethod: Boolean
    val isPutMethod: Boolean
    var replaceExisting: Boolean
    val mustExist: Boolean
    val mustNotExist: Boolean

    // whether the intent to create or replace is ambiguous (relevant during a PUT method)
    val intentIsAmbiguous: Boolean

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
            if(legal) assertLegalId(groupId!!, LDAP_COMPATIBLE_SLUG_REGEX)
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

        // `If-None-Match` star on POST should always fail
        if(call.request.httpMethod === HttpMethod.Post && ifNoneMatch?.isStar == true) {
            throw PreconditionFailedException("If-None-Match: * on POST will never succeed")
        }

        /*
             (default)          -- create new, or replace existing
             if-match: *        -- replace existing, fail if none exists
             if-match: TAG      -- replace existing, fail if resource does not match tag
             if-none-match: *   -- create new resource, fail if already exists
             if-none-match: TAG -- create new or replace existing, fail if existing resource matches tag
         */

        // cache whether it is a POST request
        isPostMethod = call.request.httpMethod == HttpMethod.Post

        // cache whether it is a PUT request
        isPutMethod = call.request.httpMethod == HttpMethod.Put

        // whether the replacement of an existing resource is acceptable
        replaceExisting = isPutMethod && (ifNoneMatch == null || !ifNoneMatch.isStar)

        // resource must exist
        mustExist = ifMatch != null

        // resource must not exist
        mustNotExist = ifNoneMatch?.isStar == true

        // precondition conflict
        if((mustExist && mustNotExist)
            || (ifNoneMatch != null && ifMatch?.etags?.intersect(ifNoneMatch.etags)?.isNotEmpty() == true)
        ) {
            throw PreconditionFailedException("Impossible precondition; If-Match and If-None-Match are in conflict")
        }

        // whether the intent to create or replace is ambiguous
        intentIsAmbiguous = isPutMethod && !mustExist && !mustNotExist

//        // anything other than PUT should fail?
//        if(!isPutMethod && mustNotExist) {
//            throw PreconditionFailedException("")
//        }
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
        if(isPostMethod) return

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

        // compose output etag
        var outputEtag = if(bindings.size == 1) {
            bindings[0]["__mms_etag"]!!.jsonObject["value"]!!.jsonPrimitive.content
        }  else {
            bindings.map { it["__mms_etag"]!!.jsonObject["value"]!!.jsonPrimitive.content }
                .distinct().joinToString(":").sha256()
        }

        // aggregation
        if(bindings[0]["elementEtag"]?.jsonObject != null) {
            outputEtag += ":"+bindings.map { it["elementEtag"]!!.jsonObject["value"]!!.jsonPrimitive.content }
                .sorted().joinToString(":").sha256()
        }

        // set etag value in response header
        call.response.header(HttpHeaders.ETag, outputEtag)

        // check preconditions
        checkPreconditions(outputEtag)
    }

    fun extractResourceEtag(model: KModel, resourceNode: Resource, allEtags: Boolean=false): String {
        // resource etags
        val resourceEtags = model.listObjectsOfProperty(if(allEtags) null else resourceNode, MMS.etag).toList()
        var outputEtag = when(resourceEtags.size) {
            0 -> throw ServerBugException("Constructed model did not contain any etag values.")
            1 -> resourceEtags[0].asLiteral().string
            else -> resourceEtags.map { it.asLiteral().string }.sorted()
                .joinToString(":").sha256()
        }

        // get all element etags from aggregator sorted
        val elementEtags = model.createResource(MMS_URNS.SUBJECT.aggregator)
            .listProperties(MMS.etag).toList().map { it.string }.sorted()

        // aggregated elements exit
        if(elementEtags.isNotEmpty()) {
            // modify output etag
            outputEtag += ":${elementEtags.joinToString(":").sha256()}"
        }

        // set etag value in response header
        call.response.header(HttpHeaders.ETag, outputEtag)

        return outputEtag
    }

    fun handleWrittenResourceEtag(model: KModel, resourceUri: String, allEtags: Boolean=false): String {
        // create resource node in model
        val resourceNode = model.createResource(resourceUri)

        // resource not exists; server error
        if(!resourceNode.listProperties().hasNext()) {
            throw ServerBugException("Model from select/construct response missing resource ETag")
        }

        return extractResourceEtag(model, resourceNode, allEtags)
    }

    @JvmOverloads
    fun handleEtagAndPreconditions(model: KModel, resourceUri: String?, allEtags: Boolean=false) {
        // single resource
        if(resourceUri != null) {
            // create resource node in model
            val resourceNode = model.createResource(resourceUri)

            // resource not exists; 404
            if(!resourceNode.listProperties().hasNext()) {
                throw Http404Exception(call.request.path())
            }

            // get resource etag
            val etag = extractResourceEtag(model, resourceNode, allEtags)

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
        val resourceEtags = mutableListOf<String>()
        val elementEtags = mutableListOf<String>()

        // each node that has an etag...
        model.listSubjectsWithProperty(MMS.etag).forEach { subject ->
            val etagStmts = subject.listProperties(MMS.etag)

            // ...that is of the specified type
            if(subject.hasProperty(RDF.type, resourceType)) {
                // add all etags to resource list
                resourceEtags.addAll(etagStmts.mapWith { statement ->
                    statement.`object`.asLiteral().string
                }.toList())
            }
            // other
            else {
                if(MMS_URNS.SUBJECT.aggregator === subject.uri) {
                    // add all etags to aggregator list
                    elementEtags.addAll(etagStmts.mapWith { statement ->
                        statement.`object`.asLiteral().string
                    }.toList())
                }
            }
        }

        // sort list of etags and hash them
        val etag = resourceEtags.sorted().toList().joinToString(":").sha256()

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



    /**
     * Adds a requirement to the query conditions that asserts a valid `refSource` or `commitSource`, usually follows
     * a call to [RdfModeler.normalizeRefOrCommit] within a [Layer1Context.filterIncomingStatements] block.
     */
    fun ConditionsGroup.appendRefOrCommit(): ConditionsGroup {
        return append {
            require("validSource") {
                handler = { prefixes -> "Invalid ${if(refSource != null) "ref" else "commit"} source" to HttpStatusCode.BadRequest }

                """
                    ${if(refSource != null) """
                        graph m-graph:Schema {
                            ?__mms_refSourceClass rdfs:subClassOf* mms:Ref .
                        }
               
                        graph mor-graph:Metadata {         
                            ?_refSource a ?__mms_refSourceClass ;
                                mms:commit ?__mms_commitSource ;
                                .
                        }
                    """ else ""} 
                    graph mor-graph:Metadata {
                       ?__mms_commitSource a mms:Commit .
                    }
                """
            }
        }
    }

    fun ConditionsBuilder.appendPreconditions(inject: ((String)->String)={it}) {
        // preconditions apply
        if(ifMatch?.isStar == false || ifNoneMatch != null) {
            require("userPreconditions") {
                handler = { "User preconditions failed" to HttpStatusCode.PreconditionFailed }

                inject(injectPreconditions())
            }
        }
    }
}

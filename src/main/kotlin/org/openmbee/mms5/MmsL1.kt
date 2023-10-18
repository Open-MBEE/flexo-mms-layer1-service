package org.openmbee.mms5

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
import org.apache.jena.query.QueryFactory
import org.apache.jena.query.QueryParseException
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
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.OWL
import org.apache.jena.vocabulary.RDF
import org.apache.jena.vocabulary.RDFS
import org.openmbee.mms5.plugins.UserDetailsPrincipal
import org.openmbee.mms5.plugins.client
import java.io.ByteArrayOutputStream
import java.util.*

class ParamNotParsedException(paramId: String): Exception("The {$paramId} is being used but the param was never parsed.")

val DEFAULT_BRANCH_ID = "master"

private val ETAG_VALUE = """(W/)?"([\w-]+)"""".toRegex()
private val COMMA_SEPARATED = """\s*,\s*""".toRegex()

private val ETAG_PROPERTY = ResourceFactory.createProperty("urn:mms:etag")

private val MIGZ_BLOCK_SIZE = 1536 * 1024

class ParamNormalizer(val mms: MmsL1Context, val call: ApplicationCall =mms.call) {
    fun group(legal: Boolean=false) {
        mms.groupId = call.parameters["groupId"]?: throw Http400Exception("Requisite {groupId} parameter was null")
        if(legal) assertLegalId(mms.groupId!!, """[/?&=.,_\pL-]{3,256}""".toRegex())
    }

    fun org(legal: Boolean=false) {
        mms.orgId = call.parameters["orgId"]
        if(legal) assertLegalId(mms.orgId!!)
    }

    fun collection(legal: Boolean=false) {
        mms.collectionId = call.parameters["collectionId"]
        if(legal) assertLegalId(mms.collectionId!!)
    }

    fun repo(legal: Boolean=false) {
        mms.repoId = call.parameters["repoId"]
        if(legal) assertLegalId(mms.repoId!!)
    }

    fun commit(legal: Boolean=false) {
        mms.commitId = call.parameters["commitId"]?: throw Http400Exception("Requisite {commitId} parameter was null")
        if(legal) assertLegalId(mms.commitId)
    }

    fun lock(legal: Boolean=false) {
        mms.lockId = call.parameters["lockId"]
        if(legal) assertLegalId(mms.lockId!!)
    }

    fun branch(legal: Boolean=false) {
        mms.branchId = call.parameters["branchId"]
        if(legal) assertLegalId(mms.branchId!!)
    }

    fun diff() {
        mms.diffId = call.parameters["diffId"]
    }

    fun policy(legal: Boolean=false) {
        mms.policyId = call.parameters["policyId"]
        if(legal) assertLegalId(mms.policyId!!)
    }

    fun inspect() {
        val inspectValue = call.parameters["inspect"]?: ""
        mms.inspectOnly = if(inspectValue.isNotEmpty()) {
            if(inspectValue != "inspect") {
                throw Http404Exception(call.request.path())
            } else true
        } else false
    }
}


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

class Sanitizer(val mms: MmsL1Context, val node: Resource) {
    private val explicitUris = hashMapOf<String, Resource>()
    private val explicitUriSets = hashMapOf<String, List<Resource>>()
    private val explicitLiterals = hashMapOf<String, String>()
    private val bypassProperties = hashSetOf<String>()

    fun setProperty(property: Property, value: Resource, unsettable: Boolean?=false) {
        // user document includes triple(s) about this property; ensure value is acceptable
        node.listProperties(property).forEach { input ->
            // not acceptable
            if(input.`object` != value) throw ConstraintViolationException("user not allowed to set `${mms.prefixes.terse(property)}` property${if(unsettable == true) "" else " to anything other than <${node.uri}>"}")

            // verbose
            mms.log("Removing statement from user input: ${input.asTriple()}")

            // remove from model
            input.remove()
        }

        // set value
        explicitUris[property.uri] = value
    }

    fun setProperty(property: Property, value: String, unsettable: Boolean?=false) {
        // user document includes triple(s) about this property; ensure value is acceptable
        node.listProperties(property).forEach { input ->
            // not acceptable
            if(!input.`object`.isLiteral || input.`object`.asLiteral().string != value) throw ConstraintViolationException("user not allowed to set `${mms.prefixes.terse(property)}` property${if(unsettable == true) "" else " to anything other than \"${value}\"`"}")

            // verbose
            mms.log("Removing statement from user input: ${input.asTriple()}")

            // remove from model
            input.remove()
        }

        // set value
        explicitLiterals[property.uri] = value
    }

    fun setProperty(property: Property, value: List<Resource>, unsettable: Boolean?=false) {
        // user document includes triple(s) about this property; ensure value is acceptable
        node.listProperties(property).forEach { input ->
            // not acceptable
            if(value.contains(input.`object`)) {
                throw ConstraintViolationException("user not allowed to set `${mms.prefixes.terse(property)}` property${
                    if(unsettable == true) ""
                    else " to anything not included in [${value.joinToString(",") { "<${it.uri}>" }}]"
                }")
            }

            // verbose
            mms.log("Removing statement from user input: ${input.asTriple()}")

            // remove from model
            input.remove()
        }

        // set value
        explicitUriSets[property.uri] = value
    }

    fun bypass(property: Property) {
        bypassProperties.add(property.uri)
    }

    fun finalize() {
        // check each property
        node.listProperties().forEach {
            val predicateUri = it.predicate.uri

            // bypass property
            if(bypassProperties.contains(predicateUri)) {
                return@forEach;
            }
            // sensitive property
            else if(predicateUri.contains(FORBIDDEN_PREDICATES_REGEX)) {
                throw ConstraintViolationException("user not allowed to set <$predicateUri> property because it belongs to a restricted namespace")
            }
        }

        // add back explicit properties
        for(entry in explicitUris) {
            val property = ResourceFactory.createProperty(entry.key)
            node.addProperty(property, entry.value)
        }

        // add back explicit literals
        for(entry in explicitLiterals) {
            val property = ResourceFactory.createProperty(entry.key)
            node.addProperty(property, entry.value)
        }
    }
}

fun Quad.isSanitary(): Boolean {
    val predicateUri = this.predicate.uri
    return predicateUri.contains(FORBIDDEN_PREDICATES_REGEX)
}

class RdfModeler(val mms: MmsL1Context, val baseIri: String, val content: String="${mms.prefixes}\n${mms.requestBody}") {
    val model = KModel(mms.prefixes) {
        parseTurtle(content, this, baseIri)
    }

    private fun resourceFromParamPrefix(prefixId: String, suffix: String?=null): Resource {
        val uri = mms.prefixes[prefixId]?: throw ParamNotParsedException(prefixId)

        return model.createResource(uri+(suffix?: ""))
    }

    fun userNode(): Resource {
        return resourceFromParamPrefix("mu")
    }

    fun groupNode(): Resource {
        return resourceFromParamPrefix("mg")
    }

    fun orgNode(): Resource {
        return resourceFromParamPrefix("mo")
    }

    fun collectionNode(): Resource {
        return resourceFromParamPrefix("moc")
    }

    fun repoNode(): Resource {
        return resourceFromParamPrefix("mor")
    }

    fun branchNode(): Resource {
        return resourceFromParamPrefix("morb")
    }

    fun commitNode(): Resource {
        return resourceFromParamPrefix("morc")
    }

    fun lockNode(): Resource {
        return resourceFromParamPrefix("morl")
    }

    fun diffNode(): Resource {
        return resourceFromParamPrefix("mord")
    }

    fun policyNode(): Resource {
        return resourceFromParamPrefix("mp")
    }

    fun transactionNode(subTxnId: String?=null): Resource {
        return resourceFromParamPrefix("mt", subTxnId)
    }

    fun normalizeRefOrCommit(node: Resource) {
        val refs = node.listProperties(MMS.ref).toList()
        val commits = node.listProperties(MMS.commit).toList()
        val sourceCount = refs.size + commits.size
        if(1 == sourceCount) {
            if(1 == refs.size) {
                mms.refSource = if(!refs[0].`object`.isURIResource) {
                    throw ConstraintViolationException("object of `mms:ref` predicate must be an IRI")
                } else {
                    refs[0].`object`.asResource().uri
                }

                refs[0].remove()
            }
            else {
                mms.commitSource = if(!commits[0].`object`.isURIResource) {
                    throw ConstraintViolationException("object of `mms:commit` predicate must be an IRI")
                } else {
                    commits[0].`object`.asResource().uri
                }

                commits[0].remove()
            }
        }
        else if(0 == sourceCount) {
            throw ConstraintViolationException("must specify a ref or commit source using `mms:ref` or `mms:commit` predicate, respectively")
        }
        else if(sourceCount > 1) {
            throw ConstraintViolationException("must specify exactly one ref or commit; but too many sources were specified")
        }
    }

    fun Resource.extract1OrMoreUris(predicate: Property): List<Resource> {
        val statements = listProperties(predicate).toList()
        if(statements.isEmpty()) {
            throw ConstraintViolationException("missing triples having required property `${mms.prefixes.terse(predicate)}`")
        }

        return statements.map {
            if(!it.`object`.isURIResource) {
                throw ConstraintViolationException("object of `${mms.prefixes.terse(predicate)}` predicate must be an IRI")
            }

            it.`object`.asResource()
        }
    }

    fun Resource.extractExactly1Uri(predicate: Property): Resource {
        val statements = listProperties(predicate).toList()
        if(statements.isEmpty()) {
            throw ConstraintViolationException("missing triples having required property `${mms.prefixes.terse(predicate)}`")
        }
        else if(statements.size > 1) {
            throw ConstraintViolationException("must specify exactly one `${mms.prefixes.terse(predicate)}` but ${statements.size} were specified")
        }
        else if(!statements[0].`object`.isURIResource) {
            throw ConstraintViolationException("object of `${mms.prefixes.terse(predicate)}` predicate must be an IRI")
        }

        return statements[0].`object`.asResource()
    }

    fun Resource.sanitizeCrudObject(setup: (Sanitizer.()->Unit)?=null) {
        removeNonLiterals(DCTerms.title)

        if(setup != null) {
            Sanitizer(mms, this@sanitizeCrudObject).apply {
                setup(this)
                finalize()
            }
        }
    }
}

data class EtagQualifier(
    val etags: HashSet<String>,
    val isStar: Boolean=false,
)

private val STAR_ETAG_QUALIFIER = EtagQualifier(hashSetOf(), true)


private fun replaceValuesDirectives(sparql: String, vararg pairs: Pair<String, List<String>>): String {
    var replaced = sparql
    for(pair in pairs) {
        replaced = replaced.replace("""\n\s*#+\s*@values\s+${pair.first}\s*\n""".toRegex(), pair.second.joinToString(" ") { escapeLiteral(it) })
    }
    return replaced
}

class MmsL1Context(val call: ApplicationCall, val requestBody: String, val permission: Permission) {
    val log = call.application.log
    val transactionId = UUID.randomUUID().toString()

    val session = call.principal<UserDetailsPrincipal>()

    val ifMatch = parseEtagHeader(HttpHeaders.IfMatch)
    val ifNoneMatch = parseEtagHeader(HttpHeaders.IfNoneMatch)

    val userId: String
    val groups: List<String>

    /**
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

    var inspectOnly: Boolean = false

    var refSource: String? = null
    var commitSource: String? = null

    var commitMessage: String? = null

    val requestPath = call.request.path()
    val requestMethod = call.request.httpMethod.value
    val requestBodyContentType = call.request.contentType().toString()

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

    init {
        val groupsSummary = session?.groups?.joinToString(",") { "<$it>" }?: "none"
        log("${call.request.httpMethod.value} ${call.request.path()} @${session?.name?: "{anonymous}"} in (${groupsSummary})")

        // missing userId
        if((session == null) || session.name.isBlank()) {
            throw AuthorizationRequiredException("User not authenticated")
        }

        // save
        userId = session.name
        groups = session.groups
    }

    fun log(message: String) {
        log.debug("txn/${transactionId}: $message")
    }

    fun pathParams(setup: ParamNormalizer.()->Unit): ParamNormalizer {
        return ParamNormalizer(this).apply{
            setup()
        }
    }


    /**
     * Process RDF body from user request, for normalizing content and sanitizing inputs.
     */
    fun filterIncomingStatements(basePrefixId: String, setup: RdfModeler.()->Resource): String {
        val baseIri = prefixes[basePrefixId]?: throw ParamNotParsedException(basePrefixId)

        return KModel(prefixes) {
            add(RdfModeler(this@MmsL1Context, baseIri).setup().listProperties())
        }.stringify(emitPrefixes=false)
    }


    fun parseConstructResponse(responseText: String, setup: RdfModeler.()->Unit): KModel {
        return RdfModeler(this, prefixes["m"]!!, responseText).apply(setup).model
    }


    fun checkPrefixConflicts() {
        // parse query
        val sparqlQueryAst = try {
            QueryFactory.create(requestBody)
        } catch(parse: Exception) {
            // on prefix error, retry with default prefixes
            if(parse is QueryParseException && "Unresolved prefixed name".toRegex().containsMatchIn(parse.message?: "")) {
                try {
                    QueryFactory.create("$prefixes\n$requestBody")
                }
                catch(parseAgain: Exception) {
                    throw QuerySyntaxException(parseAgain)
                }
            }
            else {
                throw QuerySyntaxException(parse)
            }
        }

        // ref query prefixes
        val queryPrefixes = sparqlQueryAst.prefixMapping.nsPrefixMap

        // each mms prefix
        for((id, iri) in prefixes.map) {
            // user query includes this prefix id
            if(queryPrefixes[id] != null) {
                // user wants to use a different IRI for this prefix
                if(queryPrefixes[id] != iri) {
                    throw ForbiddenPrefixRemapException(id, iri)
                }
                // otherwise, allow redundant prefix declaration
            }
        }
    }


    /**
     * Adds a requirement to the query conditions that asserts a valid `refSource` or `commitSource`, usually follows
     * a call to [RdfModeler.normalizeRefOrCommit] within a [MmsL1Context.filterIncomingStatements] block.
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

    fun ConditionsGroup.appendSrcRef(): ConditionsGroup {
        return append {
            require("validSourceRef") {
                handler = { prefixes -> "Invalid source ref" to HttpStatusCode.BadRequest }

                """
                    graph m-graph:Schema {
                        ?srcRefClass rdfs:subClassOf* mms:Ref .
                    }
           
                    graph mor-graph:Metadata {         
                        ?srcRef a ?srcRefClass ;
                            mms:commit ?srcCommit ;
                            .
                    }
                """
            }
        }
    }

    fun ConditionsGroup.appendDstRef(): ConditionsGroup {
        return append {
            require("validSourceRef") {
                handler = { prefixes -> "Invalid destination ref" to HttpStatusCode.BadRequest }

                """
                    graph m-graph:Schema {
                        ?dstRefClass rdfs:subClassOf* mms:Ref .
                    }
           
                    graph mor-graph:Metadata {         
                        ?dstRef a ?dstRefClass ;
                            mms:commit ?dstCommit ;
                            .
                    }
                """
            }
        }
    }

    fun buildSparqlUpdate(setup: UpdateBuilder.() -> Unit): String {
        return UpdateBuilder(this).apply { setup() }.toString()
    }

    fun buildSparqlQuery(setup: QueryBuilder.() -> Unit): String {
        return QueryBuilder(this).apply { setup() }.toString()
    }

    @OptIn(InternalAPI::class)
    suspend fun executeSparqlUpdate(pattern: String, setup: (Parameterizer.() -> Parameterizer)?=null): String {
        var sparql = Parameterizer(pattern.trimIndent()).apply {
            if(setup != null) setup()
            prefixes(prefixes)
        }.toString()

        sparql = replaceValuesDirectives(sparql,
            "groupId" to groups,
        )

        log("Executing SPARQL Update:\n$sparql")

        return handleSparqlResponse(client.post(call.application.quadStoreUpdateUrl) {
            headers {
                append(HttpHeaders.Accept, ContentType.Application.Json)
            }
            contentType(RdfContentTypes.SparqlUpdate)
            body=sparql
        })
    }

    @OptIn(InternalAPI::class)
    suspend fun executeSparqlQuery(pattern: String, acceptType: ContentType, defaultGraph: String?=null, setup: (Parameterizer.() -> Unit)?=null): String {
        var sparql = Parameterizer(pattern).apply {
            if(setup != null) setup()
            else prefixes(prefixes)
        }.toString()

        sparql = replaceValuesDirectives(sparql,
            "groupId" to groups,
        )

        log("Executing SPARQL Query:\n$sparql")

        return handleSparqlResponse(client.post(call.application.quadStoreQueryUrl) {
            headers {
                append(HttpHeaders.Accept, acceptType)
            }
            contentType(RdfContentTypes.SparqlQuery)
            if (defaultGraph != null) {
                parameter("default-graph-uri", defaultGraph)
            }
            body=sparql
        })
    }


    suspend fun executeSparqlConstructOrDescribe(pattern: String, defaultGraph: String?=null, setup: (Parameterizer.() -> Unit)?=null): String {
        return executeSparqlQuery(pattern, RdfContentTypes.Turtle, defaultGraph, setup)
    }

    suspend fun executeSparqlSelectOrAsk(pattern: String, defaultGraph: String?=null, setup: (Parameterizer.() -> Unit)?=null): String {
        return executeSparqlQuery(pattern, RdfContentTypes.SparqlResultsJson, defaultGraph, setup)
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
                conditions.handle(model, mms);

                // the above always throws, so this is unreachable
            }
        }
    }

    fun injectPreconditions(): String {
        // log.info("escpaeLiteral('test'): ${escapeLiteral("test")}")
        // log.info("etags: ${ifMatch?.etags?.joinToString("; ")}")

        return """
            ${if(ifMatch?.isStar == false) """
                values ?__mms_etag {
                    ${ifMatch.etags.joinToString(" ") { escapeLiteral(it) }}
                }
            """ else ""}
            
            ${if(ifNoneMatch != null) """
                filter(?__mms_etag != ?__mms_etagNot)
                values ?__mms_etagNot {
                    ${ifNoneMatch.etags.joinToString(" ") { escapeLiteral(it) }}
                }
            """ else ""}
        """
    }

    fun assertPreconditions(builder: ConditionsBuilder, inject: (String)->String) {
        if((ifMatch != null && !ifMatch.isStar) || ifNoneMatch != null) {
            if(ifNoneMatch?.isStar == true) {
                throw BadRequestException("Cannot provide `If-None-Match: *` precondition to ${permission.id} action")
            }

            builder.require("userPreconditions") {
                handler = { "User preconditions failed" to HttpStatusCode.PreconditionFailed }

                inject(injectPreconditions())
            }
        }
    }


    fun parseEtagHeader(headerKey: String): EtagQualifier? {
        val value = call.request.header(headerKey)?.trim()
        return if(value != null) {
            if(value == "*") STAR_ETAG_QUALIFIER
            else EtagQualifier(
                value.split(COMMA_SEPARATED).map {
                    ETAG_VALUE.matchEntire(it)?.groupValues?.get(2)
                        ?: throw InvalidHeaderValue("$headerKey: \"$it\"")
                }.toHashSet())
        } else null
    }

    private fun checkPreconditions(etag: String) {
        // `create` operation; exit
        if(permission.crud == Crud.CREATE) return


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

    fun handleEtagAndPreconditions(response: JsonObject) {
        // destructure bindings
        val bindings = response["results"]!!.jsonObject["bindings"]!!.jsonArray

        // resource does not exist
        if(0 == bindings.size) {
            throw Http404Exception(call.request.path())
        }

        // compose etag
        val etag = if(bindings.size == 1) {
            bindings[0].jsonObject["__mms_etag"]!!.jsonObject["value"]!!.jsonPrimitive.content
        }  else {
            bindings.joinToString(":") { it.jsonObject["__mms_etag"]!!.jsonObject["value"]!!.jsonPrimitive.content }.sha256()
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

suspend fun MmsL1Context.guardedPatch(objectKey: String, graph: String, preconditions: ConditionsGroup) {
    val baseIri = prefixes[objectKey]!!

    // parse query
    val sparqlUpdateAst = try {
        UpdateFactory.create(requestBody, baseIri)
    } catch(parse: Exception) {
        throw UpdateSyntaxException(parse)
    }

    var deleteBgpString = ""
    var insertBgpString = ""
    var whereString = ""

    val operations = sparqlUpdateAst.operations

    val dataFilter = quadDataFilter(baseIri)
    val patternFilter = quadPatternFilter(baseIri)

    for(update in operations) {
        when(update) {
            is UpdateDataDelete -> deleteBgpString = asSparqlGroup(update.quads, dataFilter)
            is UpdateDataInsert -> insertBgpString = asSparqlGroup(update.quads, dataFilter)
            is UpdateDeleteWhere -> {
                deleteBgpString = asSparqlGroup(update.quads, patternFilter)
                whereString = deleteBgpString
            }
            is UpdateModify -> {
                if(update.hasDeleteClause()) {
                    deleteBgpString = asSparqlGroup(update.deleteQuads, patternFilter)
                }

                if(update.hasInsertClause()) {
                    insertBgpString = asSparqlGroup(update.insertQuads, patternFilter)
                }

                whereString = asSparqlGroup(update.wherePattern.apply {
                    visit(NoQuadsElementVisitor)
                })
            }
            else -> throw UpdateOperationNotAllowedException("SPARQL ${update.javaClass.simpleName} not allowed here")
        }
    }

    log("Guarded patch update:\n\n\tINSERT: $insertBgpString\n\n\tDELETE: $deleteBgpString\n\n\tWHERE: $whereString")

    val conditions = preconditions.append {
        if(whereString.isNotEmpty()) {
            require("userWhere") {
                handler = { "User update condition is not satisfiable" to HttpStatusCode.PreconditionFailed }

                """
                    graph $graph {
                        $whereString
                    }
                """
            }
        }

        // assert any HTTP preconditions supplied by the user
        assertPreconditions(this) {
            """
                graph $graph {
                    $it
                }
            """
        }
    }


    // generate sparql update
    val updateString = buildSparqlUpdate {
        delete {
            graph(graph) {
                raw("""
                    # delete old etag
                    $objectKey: mms:etag ?__mms_etag .
                """)

                if(deleteBgpString.isNotEmpty()) {
                    raw(deleteBgpString)
                }
            }
        }
        insert {
            txn()

            graph(graph) {
                raw("""
                    # set new etag
                    $objectKey: mms:etag ?_txnId .
                """)

                if(insertBgpString.isNotEmpty()) {
                    raw(insertBgpString)
                }
            }
        }
        where {
            raw(*conditions.requiredPatterns())

            graph(graph) {
                raw("""
                    # bind old etag for deletion
                    $objectKey: mms:etag ?__mms_etag .
                """)
            }
        }
    }


    executeSparqlUpdate(updateString) {
        prefixes(prefixes)

        literal(
            "_txnId" to transactionId
        )
    }


    // create construct query to confirm transaction and fetch base model details
    val constructString = buildSparqlQuery {
        construct {
            txn()

            raw("""
                $objectKey: ?w_p ?w_o .
            """)
        }
        where {
            group {
                txn()

                raw("""
                    graph $graph {
                        $objectKey: ?w_p ?w_o .
                    }
                """)
            }
            raw("""union ${conditions.unionInspectPatterns()}""")
        }
    }

    val constructResponseText = executeSparqlConstructOrDescribe(constructString)

    log.info("Post-update construct response:\n$constructResponseText")

    val constructModel = validateTransaction(constructResponseText, conditions)

    // set etag header
    call.response.header(HttpHeaders.ETag, transactionId)

    // forward response to client
    call.respondText(
        constructResponseText,
        contentType = RdfContentTypes.Turtle,
    )

    // delete transaction
    run {
        val dropResponseText = executeSparqlUpdate("""
            delete where {
                graph m-graph:Transactions {
                    mt: ?p ?o .
                }
            }
        """)

        log("Transaction delete response:\n$dropResponseText")
    }
}

fun MmsL1Context.genCommitUpdate(conditions: ConditionsGroup, delete: String="", insert: String="", where: String=""): String {
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

fun MmsL1Context.genDiffUpdate(diffTriples: String="", conditions: ConditionsGroup?=null, rawWhere: String?=null): String {
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


suspend fun ApplicationCall.mmsL1(permission: Permission, postponeBody: Boolean?=false, setup: suspend MmsL1Context.()->Unit): MmsL1Context {
    val requestBody = if(postponeBody == true) "" else receiveText()

    return MmsL1Context(this, requestBody, permission).apply{ setup() }
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

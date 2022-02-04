package org.openmbee

import io.ktor.application.*
import io.ktor.client.request.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.util.*
import org.apache.commons.io.IOUtils
import org.apache.jena.rdf.model.Property
import org.apache.jena.rdf.model.RDFNode
import org.apache.jena.rdf.model.Resource
import org.apache.jena.riot.RDFLanguages
import org.apache.jena.riot.RDFParser
import org.apache.jena.riot.system.ErrorHandlerFactory
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.OWL
import org.apache.jena.vocabulary.RDF
import org.apache.jena.vocabulary.RDFS
import org.openmbee.plugins.client
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import org.apache.jena.rdf.model.ResourceFactory
import org.apache.jena.rdf.model.impl.PropertyImpl
import java.nio.charset.StandardCharsets
import java.util.*

class ParamNotParsedException(paramId: String): Exception("The {$paramId} is being used but the param was never parsed.")

private val ETAG_VALUE = """(W/)?"([\w-]+)"""".toRegex()
private val COMMA_SEPARATED = """\s*,\s*""".toRegex()

private val ETAG_PROPERTY = ResourceFactory.createProperty("mms://etag")

class ParamNormalizer(val mms: MmsL1Context, val call: ApplicationCall =mms.call) {
    fun user() {
        // missing userId
        if(mms.userId.isEmpty()) {
            throw AuthorizationRequiredException("Missing header: `MMS5-User`")
        }
    }

    fun org(legal: Boolean=false) {
        mms.orgId = call.parameters["orgId"]
        if(legal) assertLegalId(mms.orgId!!)
    }

    fun repo(legal: Boolean=false) {
        mms.repoId = call.parameters["repoId"]
        if(legal) assertLegalId(mms.repoId!!)
    }

    fun commit(legal: Boolean=false) {
        mms.commitId = call.parameters["commitId"]?: throw Exception("Requisite {commitId} parameter was null")
        if(legal) assertLegalId(mms.commitId!!)
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

    fun inspect() {
        val inspectValue = call.parameters["inspect"]?: ""
        mms.inspectOnly = if(inspectValue.isNotEmpty()) {
            if(inspectValue != "inspect") {
                throw NotFoundException()
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

fun Resource.sanitizeCrudObject() {
    removeNonLiterals(DCTerms.title)

    listProperties().toList().forEach {
        val predicateUri = it.predicate.uri
        if(predicateUri.contains(FORBIDDEN_PREDICATES_REGEX)) {
            it.remove()
        }
    }
}

class RdfModeler(val mms: MmsL1Context, val baseIri: String) {
    val model = KModel(mms.prefixes)

    init {
        val putContent = "${mms.prefixes}\n${mms.requestBody}"

        // parse input document
        RDFParser.create()
            .source(IOUtils.toInputStream(putContent, StandardCharsets.UTF_8))
            .lang(RDFLanguages.TURTLE)
            .errorHandler(ErrorHandlerFactory.errorHandlerWarn)
            .base(baseIri)
            .parse(model)
    }

    private fun resourceFromParamPrefix(prefixId: String): Resource {
        val uri = mms.prefixes[prefixId]?: throw ParamNotParsedException(prefixId)

        return model.createResource(uri)
    }

    fun userNode(): Resource {
        return resourceFromParamPrefix("mu")
    }

    fun orgNode(): Resource {
        return resourceFromParamPrefix("mo")
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
        return resourceFromParamPrefix("morcl")
    }

    fun diffNode(): Resource {
        return resourceFromParamPrefix("morcld")
    }

    fun transactionNode(): Resource {
        return resourceFromParamPrefix("mt")
    }

    fun normalizeRefOrCommit(node: Resource) {
        val refs = node.listProperties(MMS.ref).toList()
        val commits = node.listProperties(MMS.commit).toList()
        val sourceCount = refs.size + commits.size
        if(1 == sourceCount) {
            if(1 == refs.size) {
                mms.refSource = if(!refs[0].`object`.isURIResource) {
                    throw Exception("Ref source must be an IRI")
                } else {
                    refs[0].`object`.asResource().uri
                }
            }
            else {
                mms.commitSource = if(!commits[0].`object`.isURIResource) {
                    throw Exception("Commit source must be an IRI")
                } else {
                    commits[0].`object`.asResource().uri
                }
            }
        }
        else if(0 == sourceCount) {
            throw Exception("Must specify a ref or commit source using mms:ref or mms:commit predicate, respectively.")
        }
        else if(sourceCount > 1) {
            throw Exception("Too many sources specified.")
        }
    }
}

data class EtagQualifier(
    val etags: HashSet<String>,
    val isStar: Boolean=false,
)

private val STAR_ETAG_QUALIFIER = EtagQualifier(hashSetOf(), true)


// @Serializable
// data class SparqlResultsJson(
//     val head: JsonObject,
//     val boolean: Boolean?=null,
//     val results: ResultsObject
// ) {
//
// }

class MmsL1Context(val call: ApplicationCall, val requestBody: String, val permission: Permission) {
    val log = call.application.log
    val transactionId = UUID.randomUUID().toString()

    val ifMatch = parseEtagHeader(HttpHeaders.IfMatch)
    val ifNoneMatch = parseEtagHeader(HttpHeaders.IfNoneMatch)

    val userId = call.mmsUserId
    var orgId: String? = null
    var repoId: String? = null
    var commitId: String = transactionId
    var lockId: String? = null
    var branchId: String? = null
    var diffId: String? = null

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
            orgId = orgId,
            repoId = repoId,
            branchId = branchId,
            commitId = commitId,
            lockId = lockId,
            diffId = diffId,
            transactionId = transactionId,
        )

    fun pathParams(setup: ParamNormalizer.()->Unit): ParamNormalizer {
        return ParamNormalizer(this).apply{
            setup()
        }
    }

    fun filterIncomingStatements(basePrefixId: String, setup: RdfModeler.()->Resource): String {
        val baseIri = prefixes[basePrefixId]?: throw ParamNotParsedException(basePrefixId)

        return KModel(prefixes) {
            add(RdfModeler(this@MmsL1Context, baseIri).setup().listProperties())
        }.stringify(emitPrefixes=false)
    }


    fun ConditionsGroup.appendRefOrCommit(): ConditionsGroup {
        return append {
            require("validSource") {
                handler = { prefixes -> "Invalid ${if(refSource != null) "ref" else "commit"} source" }

                """
                    ${if(refSource != null) """
                        graph m-graph:Schema {
                            ?refSourceClass rdfs:subClassOf* mms:Ref .
                        }
               
                        graph mor-graph:Metadata {         
                            ?_refSource a ?refSourceClass ;
                                mms:commit ?commitSource ;
                                .
                        }
                    """ else ""} 
                    graph mor-graph:Metadata {
                       ?commitSource a mms:Commit .
                    }
                """
            }
        }
    }

    fun buildSparqlUpdate(setup: UpdateBuilder.() -> UpdateBuilder): String {
        return UpdateBuilder(this,).setup().toString()
    }

    fun buildSparqlQuery(setup: QueryBuilder.() -> QueryBuilder): String {
        return QueryBuilder(this).setup().toString()
    }

    @OptIn(InternalAPI::class)
    suspend fun executeSparqlUpdate(pattern: String, setup: (Parameterizer.() -> Parameterizer)?=null): String {
        var sparql = Parameterizer(pattern).apply {
            if(setup != null) setup()
            prefixes(prefixes)
        }.toString()

        call.application.log.info("SPARQL Update:\n$sparql")

        return handleSparqlResponse(client.post(STORE_UPDATE_URI) {
            headers {
                append(HttpHeaders.Accept, ContentType.Application.Json)
            }
            contentType(RdfContentTypes.SparqlUpdate)
            body=sparql
        })
    }

    @OptIn(InternalAPI::class)
    suspend fun executeSparqlQuery(pattern: String, acceptType: ContentType, setup: (Parameterizer.() -> Unit)?=null): String {
        var sparql = Parameterizer(pattern).apply {
            if(setup != null) setup()
            prefixes(prefixes)
        }.toString()

        call.application.log.info("SPARQL Query:\n$sparql")

        return handleSparqlResponse(client.post(STORE_QUERY_URI) {
            headers {
                append(HttpHeaders.Accept, acceptType)
            }
            contentType(RdfContentTypes.SparqlQuery)
            body=sparql
        })
    }


    suspend fun executeSparqlConstructOrDescribe(pattern: String, setup: (Parameterizer.() -> Unit)?=null): String {
        return executeSparqlQuery(pattern, RdfContentTypes.Turtle, setup)
    }

    suspend fun executeSparqlSelectOrAsk(pattern: String, setup: (Parameterizer.() -> Unit)?=null): String {
        return executeSparqlQuery(pattern, RdfContentTypes.SparqlResultsJson, setup)
    }

    fun validateTransaction(results: String, conditions: ConditionsGroup): KModel {
        val model = KModel(prefixes) {
            parseTurtle(
                body = results,
                model = this,
            )
        }

        val transactionNode = model.createResource(prefixes["mt"])

        // transaction failed
        if(!transactionNode.listProperties().hasNext()) {
            // use response to diagnose cause
            conditions.handle(model);

            // the above always throws, so this is unreachable
        }

        return model
    }


    fun assertPreconditions(builder: ConditionsBuilder, inject: (String)->String) {
        if((ifMatch != null && !ifMatch.isStar) || ifNoneMatch != null) {
            if(ifNoneMatch?.isStar == true) {
                throw BadRequestException("Cannot provide `If-None-Match: *` precondition to ${permission.id} action")
            }

            builder.require("userPreconditions") {
                handler = { "User preconditions failed" }

                inject(""" 
                    ${if(ifNoneMatch != null) "filter(?etag != ?etagNot)" else ""}
                    
                    ${if(ifMatch != null && !ifMatch.isStar)
                        """
                            values ?etag {
                                ${ifMatch.etags.joinToString(" ") { escapeLiteral(it) }}
                            }
                        }
                    """ else ""}
                    
                    ${if(ifNoneMatch != null)
                        """
                            values ?etagNot {
                                ${ifNoneMatch.etags.joinToString(" ") { escapeLiteral(it) }}
                            }
                        }
                    """ else ""}
                """)
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

    fun checkPreconditions(results: JsonObject) {
        // resource does not exist
        val bindings = results.jsonObject["results"]!!.jsonObject["bindings"]!!.jsonArray
        if(0 == bindings.size) {
            throw NotFoundException()
        }

        // compose etag
        val etag = if(bindings.size == 1) {
            bindings[0].jsonObject["etag"]!!.jsonPrimitive.content
        }  else {
            bindings.joinToString(":") { it.jsonObject["etag"]!!.jsonPrimitive.content }.sha256()
        }

        // set etag value in response header
        call.response.header(HttpHeaders.ETag, etag)
    }

    fun checkPreconditions(model: KModel, resourceUri: String) {
        // etags
        val inspectNode = model.createResource("mms://inspect")
        val etags = inspectNode.listProperties(ETAG_PROPERTY).toList()
        val etag = if(etags.size == 1) {
            etags[0].`object`.asLiteral().string
        } else {
            etags.map { it.`object`.asLiteral().string }.sorted()
                .joinToString(":").sha256()
        }

        // no etags were parsed
        if(etags.size == 0) {
            throw Exception("Failed to parse ETag")
        }

        // set etag value in response header
        call.response.header(HttpHeaders.ETag, etag)

        // `create` operation; exit
        if(permission.crud == Crud.CREATE) return

        // create resource node in model
        val resourceNode = model.createResource(resourceUri)

        // resource not exists; 404
        if(!resourceNode.listProperties().hasNext()) {
            throw NotFoundException()
        }

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
}

suspend fun ApplicationCall.mmsL1(permission: Permission, setup: suspend MmsL1Context.()->Unit): MmsL1Context {
    val requestBody = receiveText()

    return MmsL1Context(this, requestBody, permission).apply{ setup() }
}

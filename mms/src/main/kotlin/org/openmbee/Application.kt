package org.openmbee

import io.ktor.application.*
import io.ktor.client.request.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.request.*
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
import org.openmbee.plugins.configureHTTP
import org.openmbee.plugins.configureRouting
import java.nio.charset.StandardCharsets
import java.util.*

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

fun Application.module(testing: Boolean=false) {
    configureHTTP()
    configureRouting()
}

//fun main() {
//    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
//        configureHTTP()
//        configureRouting()
//    }.start(wait = true)
//}

class AuthorizationRequiredException(message: String): Exception(message) {}

class ParamNotParsedException(paramId: String): Exception("The {$paramId} is being used but the param was never parsed.")

class Normalizer(val call: ApplicationCall) {
    val userId = call.mmsUserId
    var orgId: String? = null
    var repoId: String? = null
    var commitId: String? = null
    var lockId: String? = null
    var branchId: String? = null
    var diffId: String? = null

    fun user(): Normalizer {
        // missing userId
        if(userId.isEmpty()) {
            throw AuthorizationRequiredException("Missing header: `MMS5-User`")
        }
        return this
    }

    fun org(legal: Boolean=false): Normalizer {
        orgId = call.parameters["orgId"]
        if(legal) assertLegalId(orgId!!)
        return this
    }

    fun repo(legal: Boolean=false): Normalizer {
        repoId = call.parameters["repoId"]
        if(legal) assertLegalId(repoId!!)
        return this
    }

    fun commit(legal: Boolean=false): Normalizer {
        commitId = call.parameters["commitId"]
        if(legal) assertLegalId(commitId!!)
        return this
    }

    fun lock(legal: Boolean=false): Normalizer {
        lockId = call.parameters["lockId"]
        if(legal) assertLegalId(lockId!!)
        return this
    }

    fun branch(legal: Boolean=false): Normalizer {
        branchId = call.parameters["branchId"]
        if(legal) assertLegalId(branchId!!)
        return this
    }

    fun diff(): Normalizer {
        diffId = call.parameters["diffId"]
        return this
    }
}

suspend fun ApplicationCall.normalize(setup: Normalizer.()->Normalizer): TransactionContext {
    val norm = Normalizer(this).setup()

    val body = receiveText()

    return TransactionContext(
        userId = norm.userId,
        orgId = norm.orgId,
        repoId = norm.repoId,
        commitId = norm.commitId,
        lockId = norm.lockId,
        branchId = norm.branchId,
        diffId = norm.diffId,
        request = request,
        requestBody = body,
    )
}

class ParamNormalizer(val mms: MmsL1Context, val call: ApplicationCall=mms.call) {
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

    listProperties().forEach {
        val predicateUri = it.predicate.uri
        if(predicateUri.contains(FORBIDDEN_PREDICATES_REGEX)) {
            it.remove()
        }
    }
}

open class RdfModeler(val mms: MmsL1Context, val baseIri: String) {
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

class MmsL1Context(val call: ApplicationCall, val requestBody: String) {
    val log = call.application.log
    val transactionId = UUID.randomUUID().toString()

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
                    graph mor-graph:Metadata {
                        ${if(refSource != null) """
                            ?_refSource a/rdfs:subClassOf* mms:Ref ;
                                mms:commit ?commitSource ;
                                .
                        """ else ""} 
                       
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
        val sparql = prepareSparql(pattern) {
            if(setup != null) setup()
            prefixes(prefixes)
        }

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
    suspend fun executeSparqlQuery(pattern: String, acceptType: ContentType, setup: (Parameterizer.() -> Parameterizer)?=null): String {
        val sparql = prepareSparql(pattern) {
            if(setup != null) setup()
            prefixes(prefixes)
        }

        call.application.log.info("SPARQL Query:\n$sparql")

        return handleSparqlResponse(client.post(STORE_QUERY_URI) {
            headers {
                append(HttpHeaders.Accept, acceptType)
            }
            contentType(RdfContentTypes.SparqlQuery)
            body=sparql
        })
    }


    suspend fun executeSparqlConstructOrDescribe(pattern: String, setup: (Parameterizer.() -> Parameterizer)?=null): String {
        return executeSparqlQuery(pattern, RdfContentTypes.Turtle, setup)
    }

    suspend fun executeSparqlSelectOrAsk(pattern: String, setup: (Parameterizer.() -> Parameterizer)?=null): String {
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
}

suspend fun ApplicationCall.mmsL1(setup: suspend MmsL1Context.()->Unit): TransactionContext {
    val requestBody = receiveText()

    val norm = MmsL1Context(this, requestBody).apply{ setup() }

    val body = receiveText()

    return TransactionContext(
        userId = norm.userId,
        orgId = norm.orgId,
        repoId = norm.repoId,
        commitId = norm.commitId,
        lockId = norm.lockId,
        branchId = norm.branchId,
        diffId = norm.diffId,
        request = request,
        requestBody = body,
    )
}

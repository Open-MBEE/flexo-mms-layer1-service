package org.openmbee.mms5

import io.ktor.application.*
import io.ktor.client.request.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.util.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.commons.io.IOUtils
import org.apache.jena.rdf.model.Property
import org.apache.jena.rdf.model.RDFNode
import org.apache.jena.rdf.model.Resource
import org.apache.jena.rdf.model.ResourceFactory
import org.apache.jena.riot.RDFLanguages
import org.apache.jena.riot.RDFParser
import org.apache.jena.riot.system.ErrorHandlerFactory
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
import org.openmbee.mms5.plugins.client
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

    fun collection(legal: Boolean=false) {
        mms.collectionId = call.parameters["collectionId"]
        if(legal) assertLegalId(mms.collectionId!!)
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

class Sanitizer(val mms: MmsL1Context, val node: Resource) {
    val explicitUris = hashMapOf<String, Resource>()
    val explicitLiterals = hashMapOf<String, String>()

    fun setProperty(property: Property, value: Resource, unsettable: Boolean?=false) {
        val inputs = node.listProperties(property)

        // user document includes triple(s) about this property
        if(inputs.hasNext()) {
            // ensure value is acceptable
            for(input in inputs) {
                // not acceptable
                if(input.`object` != value) throw ConstraintViolationException("user not allowed to set `${mms.prefixes.terse(property)}` property${if(unsettable == true) "" else " to anything other than <${node.uri}>"}")

                // remove from model
                input.remove()
            }
        }

        // set value
        explicitUris[property.uri] = value
    }

    fun setProperty(property: Property, value: String, unsettable: Boolean?=false) {
        val inputs = node.listProperties(property)

        // user document includes triple(s) about this property
        if(inputs.hasNext()) {
            // ensure value is acceptable
            for(input in inputs) {
                // not acceptable
                if(!input.`object`.isLiteral || input.`object`.asLiteral().string != value) throw ConstraintViolationException("user not allowed to set `${mms.prefixes.terse(property)}` property${if(unsettable == true) "" else " to anything other than \"${value}\"`"}")

                // remove from model
                input.remove()
            }
        }

        // set value
        explicitLiterals[property.uri] = value
    }

    fun finalize() {
        // check each property
        node.listProperties().toList().forEach {
            val predicateUri = it.predicate.uri

            // sensitive property
            if(predicateUri.contains(FORBIDDEN_PREDICATES_REGEX)) {
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
    val model = KModel(mms.prefixes)

    init {
        // parse input document
        RDFParser.create()
            .source(IOUtils.toInputStream(content, StandardCharsets.UTF_8))
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
        val statements = listProperties(MMS.collects).toList()
        if(statements.isEmpty()) {
            throw ConstraintViolationException("missing triples having required property `${mms.prefixes.terse(predicate)}`")
        }

        return statements.map {
            if(!it.`object`.isURIResource) {
                throw ConstraintViolationException("object of `mms:collects` predicate must be an IRI")
            }

            it.`object`.asResource()
        }
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
    var collectionId: String? = null
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
            collectionId = collectionId,
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

    fun parseConstructResponse(responseText: String, setup: RdfModeler.()->Unit): KModel {
        return RdfModeler(this, prefixes["m"]!!, responseText).apply(setup).model
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

    fun buildSparqlUpdate(setup: UpdateBuilder.() -> Unit): String {
        return UpdateBuilder(this,).apply { setup() }.toString()
    }

    fun buildSparqlQuery(setup: QueryBuilder.() -> Unit): String {
        return QueryBuilder(this).apply { setup() }.toString()
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

    fun injectPreconditions(): String {
        log.info("escpaeLiteral('test'): ${escapeLiteral("test")}")
        log.info("etags: ${ifMatch?.etags?.joinToString("; ")}")

        return """
            ${if(ifMatch?.isStar == false) """
                values ?etag {
                    ${ifMatch.etags.joinToString(" ") { escapeLiteral(it) }}
                }
            """ else ""}
            
            ${if(ifNoneMatch != null) """
                filter(?etag != ?etagNot)
                values ?etagNot {
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
                handler = { "User preconditions failed" }

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


    fun checkPreconditions(response: JsonObject) {
        // destructure bindings
        val bindings = response["results"]!!.jsonObject["bindings"]!!.jsonArray

        // resource does not exist
        if(0 == bindings.size) {
            throw Http404Exception()
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
        // create resource node in model
        val resourceNode = model.createResource(resourceUri)

        // resource not exists; 404
        if(!resourceNode.listProperties().hasNext()) {
            throw NotFoundException()
        }

        // etags
        val etags = resourceNode.listProperties(MMS.etag).toList()
        val etag = if(etags.size == 1) {
            etags[0].`object`.asLiteral().string
        } else {
            etags.map { it.`object`.asLiteral().string }.sorted()
                .joinToString(":").sha256()
        }

        // no etags were parsed
        if(etags.size == 0) {
            throw ServerBugException("Constructed model did not contain any etag values.")
        }

        // set etag value in response header
        call.response.header(HttpHeaders.ETag, etag)

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

suspend fun MmsL1Context.guardedPatch(objectKey: String, graph: String, conditions: ConditionsGroup) {
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

    log.info("INSERT: $insertBgpString")
    log.info("DELETE: $deleteBgpString")
    log.info("WHERE: $whereString")

    conditions.append {
        if(whereString.isNotEmpty()) {
            require("userWhere") {
                handler = { "User update condition is not satisfiable" }

                """
                    graph $graph {
                        $whereString
                    }
                """
            }
        }

        assertPreconditions(this) {
            """
                graph $graph {
                    $objectKey: mms:etag ?etag .
                    
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
                    $objectKey: mms:etag ?etag .
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

            raw("""
                # match old etag
                $objectKey: mms:etag ?etag .
            """)
        }
    }


    executeSparqlUpdate(updateString)


    // create construct query to confirm transaction and fetch base model details
    val constructString = buildSparqlQuery {
        construct {
            txn()

            raw("""
                $objectKey: ?w_p ?w_o .
            """)
        }
        where {
            txn()

            group {
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

    // log
    log.info("Triplestore responded with \n$constructResponseText")

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

        // log response
        log.info(dropResponseText)
    }
}

fun MmsL1Context.genCommitUpdate(delete: String="", insert: String="", where: String=""): String {
    // generate sparql update
    return buildSparqlUpdate {
        delete {
            if(delete.isNotEmpty()) raw(delete)

            graph("mor-graph:Metadata") {
                raw("""
                    # branch no longer points to model snapshot
                    morb: mms:snapshot ?model .
            
                    # branch no longer points to previous commit
                    morb: mms:commit ?baseCommit .
                """)
            }
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
                        .
            
                    # commit data
                    morc-data: a mms:Update ;
                        mms:body ?_updateBody ;
                        mms:patch ?_patchString ;
                        mms:where ?_whereString ;
                        .
            
                    # update branch pointer
                    morb: mms:commit morc: .
            
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
        if(where.isNotEmpty()) {
            where {
                raw(where)
            }
        }
    }
}

fun MmsL1Context.genDiffUpdate(diffTriples: String="", conditions: ConditionsGroup?=null): String {
    // generate sparql update
    return buildSparqlUpdate {
        insert {
            txn(
                "mms-txn:diff" to "?diff",
                "mms-txn:commitSource" to "?commitSource",
                "mms-txn:diffInsGraph" to "?diffInsGraph",
                "mms-txn:diffDelGraph" to "?diffDelGraph",
            ) {
                autoPolicy(Scope.DIFF, Role.ADMIN_DIFF)
            }

            raw("""
                graph ?diffInsGraph {
                    ?ins_s ?ins_p ?ins_o .    
                }
                
                graph ?diffDelGraph {
                    ?del_s ?del_p ?del_o .
                }
                
                graph mor-graph:Metadata {
                    $diffTriples
                    
                    ?diff mms:id ?diffId ;
                        mms:diffSrc ?commitSource ;
                        mms:diffDst morc: ;
                        mms:insGraph ?diffInsGraph ;
                        mms:delGraph ?diffDelGraph ;
                        .
                }
            """)
        }
        where {
            if(conditions != null) raw(*conditions.requiredPatterns())

            raw("""
                graph ?srcGraph {
                    ?src_s ?src_p ?src_o .    
                }
                
                graph ?dstGraph {
                    ?dst_s ?dst_p ?dst_o .
                }
                
                graph ?srcGraph {
                    ?ins_s ?ins_p ?ins_o .
                    
                    minus {
                        ?dst_s ?dst_p ?dst_o .
                    }
                }
                
                graph ?dstGraph {
                    ?del_s ?del_p ?del_o .
                    
                    minus {
                        ?src_s ?src_p ?src_o .
                    }
                }
                
                optional {
                    graph mor-graph:Metadata {
                        ?commitSource ^mms:commit/mms:snapshot ?snapshot .
                        ?snapshot mms:graph ?sourceGraph  .
                    }
                }
                
                bind(
                    sha256(
                        concat(str(morc:), "\n", str(?commitSource))
                    ) as ?diffId
                )
                
                bind(
                    iri(
                        concat(str(morc:), "/diffs/", ?diffId)
                    ) as ?diff
                )
                
                bind(
                    iri(
                        concat(str(mor-graph:), "Diff.Ins.", ?diffId)
                    ) as ?diffInsGraph
                )
                
                bind(
                    iri(
                        concat(str(mor-graph:), "Diff.Del.", ?diffId)
                    ) as ?diffDelGraph
                )
            """)
        }
    }

}


suspend fun ApplicationCall.mmsL1(permission: Permission, setup: suspend MmsL1Context.()->Unit): MmsL1Context {
    val requestBody = receiveText()

    return MmsL1Context(this, requestBody, permission).apply{ setup() }
}

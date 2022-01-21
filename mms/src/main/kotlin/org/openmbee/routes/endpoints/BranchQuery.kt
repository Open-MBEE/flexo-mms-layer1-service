package org.openmbee.routes.endpoints

import io.ktor.application.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import org.apache.jena.graph.Node
import org.apache.jena.graph.NodeFactory
import org.apache.jena.query.Query
import org.apache.jena.query.QueryFactory
import org.apache.jena.sparql.core.PathBlock
import org.apache.jena.sparql.core.TriplePath
import org.apache.jena.sparql.core.Var
import org.apache.jena.sparql.path.Path
import org.apache.jena.sparql.path.PathFactory
import org.apache.jena.sparql.syntax.*
import org.apache.jena.sparql.syntax.syntaxtransform.ElementTransformCopyBase
import org.apache.jena.sparql.syntax.syntaxtransform.ElementTransformer
import org.apache.jena.sparql.syntax.syntaxtransform.QueryTransformOps
import org.openmbee.*


val BRANCH_GRAPH_PATH: Path = PathFactory.pathSeq(
    PathFactory.pathInverse(PathFactory.pathLink(MMS.ref.asNode())),
    PathFactory.pathLink(MMS.graph.asNode())
)

@OptIn(InternalAPI::class)
fun Application.queryBranch() {
    routing {
        post("/orgs/{orgId}/repos/{repoId}/branches/{branchId}/query/{inspect?}") {
            val inspectValue = call.parameters["inspect"]?: ""
            val inspectEnabled = if(inspectValue.isNotEmpty()) {
                if(inspectValue != "inspect") {
                    return@post call.respondText("", status = HttpStatusCode.NotFound)
                } else true
            } else false

            val orgId = call.parameters["orgId"]
            val repoId = call.parameters["repoId"]
            val branchId = call.parameters["branchId"]
            val userId = call.mmsUserId

            // missing userId
            if(userId.isEmpty()) {
                call.respondText("Missing header: `MMS5-User`")
                return@post
            }

            // create transaction context
            val context = TransactionContext(
                userId=userId,
                orgId=orgId,
                repoId=repoId,
                branchId=branchId,
                request=call.request,
            )

            // ref prefixes
            val prefixes = context.prefixes


            // read entire request body
            val inputQueryString = call.receiveText()

            // parse query
            val inputQuery = try {
                QueryFactory.create(inputQueryString)
            } catch(parse: Exception) {
                throw QuerySyntaxException(parse)
            }

            if(inputQueryString.contains(NEFARIOUS_VARIABLE_REGEX)) {
                // look in select vars
                for(resultVar in inputQuery.resultVars) {
                    if(resultVar.startsWith(MMS_VARIABLE_PREFIX)) {
                        throw NefariousVariableNameException(resultVar)
                    }
                }

                val variables = mutableListOf<Var>()
                ElementWalker.walk(inputQuery.queryPattern, VariableAccumulator(variables))
                for(variable in variables) {
                    if(variable.varName.startsWith(MMS_VARIABLE_PREFIX)) {
                        throw NefariousVariableNameException(variable.varName)
                    }
                }
            }

            val rewriter = GraphNodeRewriter(prefixes)

            val outputQuery = QueryTransformOps.transform(inputQuery, object: ElementTransformCopyBase() {
                fun transform(el: Element): Element {
                    return ElementTransformer.transform(el, this)
                }

                override fun transform(group: ElementGroup, members: MutableList<Element>): Element {
                    return ElementGroup().apply {
                        elements.addAll(members.map { transform(it) })
                    }
                }

                override fun transform(exists: ElementExists, el: Element): Element {
                    return ElementExists(transform(el))
                }

                override fun transform(exists: ElementNotExists, el: Element): Element {
                    return ElementNotExists(transform(el))
                }

                override fun transform(exists: ElementOptional, el: Element): Element {
                    return ElementOptional(transform(el))
                }

                override fun transform(union: ElementUnion, members: MutableList<Element>): Element {
                    return ElementUnion().apply {
                        elements.addAll(members.map { transform(it) })
                    }
                }

                override fun transform(minus: ElementMinus, el: Element): Element {
                    return ElementMinus(transform(el))
                }

                override fun transform(subq: ElementSubQuery, query: Query): Element {
                    return ElementSubQuery(query.apply { queryPattern = transform(queryPattern) })
                }

                override fun transform(el: ElementService?, service: Node?, elt1: Element?): Element {
                    throw ServiceNotAllowedException()
                }

                override fun transform(graph: ElementNamedGraph, gn: Node, subElt: Element): Element {
                    return ElementNamedGraph(rewriter.rewrite(gn), subElt)
                }
            }).apply {
                // rewrite from and from named
                graphURIs.replaceAll { rewriter.rewrite(it) }
                namedGraphURIs.replaceAll { rewriter.rewrite(it) }

                // // set default graph
                // graphURIs.add(0, "${MMS_VARIABLE_PREFIX}modelGraph")

                // // include repo graph in all named graphs
                // namedGraphURIs.add(0, prefixes["mor-graph"])

                // create new group
                val group = ElementGroup()

                // create metadata graph URI node
                val modelGraphVar = NodeFactory.createVariable("${MMS_VARIABLE_PREFIX}modelGraph")

                // add all prepend root elements
                rewriter.prepend.forEach { group.addElement(it) }

                // add model graph selector
                run {
                    val pathBlock = ElementPathBlock(PathBlock().apply {
                        add(
                            TriplePath(NodeFactory.createURI(prefixes["morb"]), BRANCH_GRAPH_PATH, modelGraphVar)
                        )
                    })

                    val subQuery = ElementSubQuery(Query().apply {
                        setQuerySelectType()
                        addResultVar(modelGraphVar)
                        queryPattern = ElementNamedGraph(NodeFactory.createURI("${prefixes["mor-graph"]}Metadata"), pathBlock)
                        limit = 1
                    })

                    group.addElement(subQuery)
                }

                // wrap original element in metadata graph
                group.addElement(ElementNamedGraph(modelGraphVar, queryPattern))

                // add all append root elements
                rewriter.append.forEach { group.addElement(it) }

                // set new pattern
                queryPattern = group

                // unset query result star
                if(isQueryResultStar) {
                    isQueryResultStar = false
                }

                // resetResultVars()
                log.info("vars: "+resultVars)
            }


            val outputQueryString = outputQuery.serialize()

            if(inspectEnabled) {
                call.respondText(outputQueryString)
                return@post
            }
            else {
                log.info(outputQueryString)
            }

            if(outputQuery.isSelectType || outputQuery.isAskType) {
                val queryResponseText = call.submitSparqlSelectOrAsk(outputQueryString)

                call.respondText(queryResponseText, contentType=RdfContentTypes.SparqlResultsJson)
            }
            else if(outputQuery.isConstructType || outputQuery.isDescribeType) {
                val queryResponseText = call.submitSparqlConstructOrDescribe(outputQueryString)

                call.respondText(queryResponseText, contentType=RdfContentTypes.Turtle)
            }
        }
    }
}

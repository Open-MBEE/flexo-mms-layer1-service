package org.openmbee.flexo.mms

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.jena.query.DatasetFactory
import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.riot.Lang
import org.apache.jena.riot.RDFParser
import org.apache.jena.sparql.modify.request.UpdateDataDelete
import org.apache.jena.sparql.modify.request.UpdateDataInsert
import org.apache.jena.sparql.modify.request.UpdateModify
import org.apache.jena.update.UpdateAction
import org.apache.jena.update.UpdateFactory

private const val GRAPH = "https://example.org/graphs/Staging"

private val INITIAL_TRIG = """
    <$GRAPH> {
        <urn:e:1> <urn:p:name> "one" ; <urn:p:size> 1 .
        <urn:e:2> <urn:p:name> "two" ; <urn:p:size> 2 .
        <urn:e:3> <urn:p:name> "three" .
        <urn:e:4> <urn:p:name> "four" .
        <urn:e:5> <urn:p:name> "five" .
        <urn:e:6> <urn:p:name> "untouched" .
    }
""".trimIndent()

// the shape flexo-sysmlv2 sends for a commit: remove the changed elements' triples, then insert their new state
private val SYSMLV2_STYLE_UPDATE = """
    prefix e: <urn:e:>
    prefix p: <urn:p:>
    delete {
        ?element_n ?element_p ?element_o .
    } where {
        values ?element_n { e:1 e:2 e:3 e:4 e:5 }
        optional {
            ?element_n ?element_p ?element_o .
        }
    } ;
    insert data {
        e:1 p:name "one'" .
        e:2 p:name "two'" .
        e:3 p:name "three'" .
        e:4 p:name "four'" .
        e:5 p:name "five'" .
        e:7 p:name "seven" .
    }
""".trimIndent()

private fun stagingAfter(updateStrings: List<String>): Model {
    val dataset = DatasetFactory.create()
    RDFParser.fromString(INITIAL_TRIG, Lang.TRIG).parse(dataset.asDatasetGraph())
    for(update in updateStrings) {
        UpdateAction.parseExecute(update, dataset)
    }
    return dataset.getNamedModel(GRAPH).let { ModelFactory.createDefaultModel().add(it) }
}

private fun prefixMapOf(update: String) = HashMap(UpdateFactory.create(update).prefixMapping.nsPrefixMap)

class UpdateChunkingTests : StringSpec({
    "INSERT DATA and DELETE DATA are split into blocks of the given size" {
        val update = UpdateFactory.create("""
            insert data { <urn:a> <urn:p> 1 , 2 , 3 , 4 , 5 . } ;
            delete data { <urn:a> <urn:p> 1 , 2 , 3 . }
        """.trimIndent())

        val split = splitUpdateOperations(update.operations, 2)

        split shouldHaveSize 5
        split.take(3).forEach { it.shouldBeInstanceOf<UpdateDataInsert>() }
        split.drop(3).forEach { it.shouldBeInstanceOf<UpdateDataDelete>() }
        split.take(3).sumOf { (it as UpdateDataInsert).quads.size } shouldBe 5
        split.drop(3).sumOf { (it as UpdateDataDelete).quads.size } shouldBe 3
        split.forEach { op ->
            val size = (op as? UpdateDataInsert)?.quads?.size ?: (op as UpdateDataDelete).quads.size
            size shouldBeLessThanOrEqual 2
        }
    }

    "a delete-only update keyed by a VALUES subject is split by rows" {
        val update = UpdateFactory.create(SYSMLV2_STYLE_UPDATE)

        val split = splitUpdateOperations(update.operations, 2)

        // 5 VALUES rows -> 3 deletes, 6 inserted triples -> 3 insert blocks
        split.filterIsInstance<UpdateModify>() shouldHaveSize 3
        split.filterIsInstance<UpdateDataInsert>() shouldHaveSize 3
        // deletes still come before inserts
        split.indexOfLast { it is UpdateModify } shouldBe 2
    }

    "chunked updates leave the target graph exactly as the single update does" {
        val prefixMap = prefixMapOf(SYSMLV2_STYLE_UPDATE)
        val ast = UpdateFactory.create(SYSMLV2_STYLE_UPDATE)

        val (single, _) = prepareUserUpdate(ast, prefixMap, GRAPH)
        val expected = stagingAfter(listOf(single))

        for(block in listOf(1, 2, 3, 100)) {
            for(maxBytes in listOf<Long?>(null, 1L, 400L, 100_000L)) {
                val chunked = prepareChunkedUserUpdate(ast, prefixMap, GRAPH, block, maxBytes)
                stagingAfter(chunked).isIsomorphicWith(expected) shouldBe true
            }
        }
    }

    "a request byte limit groups operations into requests under the limit" {
        val ast = UpdateFactory.create(SYSMLV2_STYLE_UPDATE)
        val prefixMap = prefixMapOf(SYSMLV2_STYLE_UPDATE)

        val requests = prepareChunkedUserUpdate(ast, prefixMap, GRAPH, 1, 700L)

        (requests.size > 1) shouldBe true
        // every request that holds more than one operation stays under the limit
        requests.filter { it.count { c -> c == ';' } > 0 }
            .forEach { it.toByteArray().size shouldBeLessThanOrEqual 700 }

        prepareChunkedUserUpdate(ast, prefixMap, GRAPH, 1, null) shouldHaveSize 1
    }

    "updates that cannot be split safely are kept whole" {
        val cases = listOf(
            // also inserts: later chunks could observe earlier chunks' insertions
            """
                delete { ?s ?p ?o } insert { ?s <urn:p:x> 1 }
                where { values ?s { <urn:e:1> <urn:e:2> <urn:e:3> } ?s ?p ?o }
            """,
            // a pattern not about the VALUES subject could match triples another chunk deletes
            """
                delete { ?s ?p ?o }
                where { values ?s { <urn:e:1> <urn:e:2> <urn:e:3> } ?s ?p ?o . ?o ?q ?r }
            """,
            // an UNDEF row matches every subject
            """
                delete { ?s ?p ?o }
                where { values ?s { <urn:e:1> <urn:e:2> undef } ?s ?p ?o }
            """,
            // two VALUES variables
            """
                delete { ?s ?p ?o }
                where { values (?s ?p) { (<urn:e:1> <urn:p:name>) (<urn:e:2> <urn:p:name>) (<urn:e:3> <urn:p:name>) } ?s ?p ?o }
            """,
        )
        for(case in cases) {
            val operations = UpdateFactory.create(case.trimIndent()).operations
            splitUpdateOperations(operations, 1) shouldHaveSize 1
        }
    }

    "a VALUES table within the block size is not split" {
        val operations = UpdateFactory.create(SYSMLV2_STYLE_UPDATE).operations
        splitUpdateOperations(operations, 5).filterIsInstance<UpdateModify>() shouldHaveSize 1
    }
})

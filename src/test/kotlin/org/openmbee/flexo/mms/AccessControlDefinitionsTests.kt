package org.openmbee.flexo.mms

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.apache.jena.query.DatasetFactory
import org.apache.jena.query.QueryExecutionFactory
import org.apache.jena.riot.Lang
import org.apache.jena.riot.RDFParser

private const val MMS = "https://mms.openmbee.org/rdf/ontology/"
private const val OBJ = "https://mms.openmbee.org/rdf/objects/"
private const val TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"
private const val SUB = "http://www.w3.org/2000/01/rdf-schema#subClassOf"

// a small hierarchy shaped like the generated AccessControl.Definitions graph
private val DEFINITIONS_TTL = """
    @prefix mms: <$MMS> .
    @prefix mms-object: <$OBJ> .
    @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

    mms:Scope a rdfs:Class .
    mms:Cluster rdfs:subClassOf mms:Scope ; mms:implies mms:Org .
    mms:Org rdfs:subClassOf mms:Scope ; mms:implies mms:Project .
    mms:Project rdfs:subClassOf mms:Scope ; mms:implies mms:Ref .
    mms:Repo rdfs:subClassOf mms:Project .
    mms:Collection rdfs:subClassOf mms:Project .
    mms:Ref rdfs:subClassOf mms:Scope .
    mms:Branch rdfs:subClassOf mms:Ref .
    mms:Lock rdfs:subClassOf mms:Ref .

    mms-object:Permission.ReadRepo a mms:Permission .
    mms-object:Permission.UpdateRepo a mms:Permission ; mms:implies mms-object:Permission.ReadRepo .
    mms-object:Permission.ReadBranch a mms:Permission .
    mms-object:Permission.UpdateBranch a mms:Permission ; mms:implies mms-object:Permission.ReadBranch .

    mms-object:Role.ReadRepo a mms:Role ; mms:permits mms-object:Permission.ReadRepo .
    mms-object:Role.WriteRepo a mms:Role ; mms:permits mms-object:Permission.UpdateRepo, mms-object:Permission.UpdateBranch .
    mms-object:Role.AdminCluster a mms:Role ; mms:permits mms-object:Permission.UpdateRepo .
    mms-object:Role.NotARole mms:permits mms-object:Permission.ReadRepo .
""".trimIndent()

private fun definitionsFrom(ttl: String): AccessControlDefinitions {
    val model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel()
    RDFParser.fromString(ttl, Lang.TURTLE).parse(model)
    val maps = listOf(mutableMapOf<String, MutableSet<String>>(), mutableMapOf(), mutableMapOf(), mutableMapOf())
    val (subClassOf, implies, types, permits) = maps
    model.listStatements().forEach { st ->
        if(!st.`object`.isURIResource) return@forEach
        val target = when(st.predicate.uri) {
            SUB -> subClassOf
            TYPE -> types
            "${MMS}implies" -> implies
            "${MMS}permits" -> permits
            else -> return@forEach
        }
        target.getOrPut(st.subject.uri) { mutableSetOf() }.add(st.`object`.asResource().uri)
    }
    return AccessControlDefinitions(subClassOf, implies, types, permits)
}

// evaluates the property path the definitions replace, as the reference for equivalence
private fun pathHolds(scopeType: String, target: String): Boolean {
    val dataset = DatasetFactory.create()
    RDFParser.fromString(DEFINITIONS_TTL, Lang.TURTLE).parse(dataset.defaultModel)
    return QueryExecutionFactory.create("""
        ask { <$MMS$scopeType> <$SUB>*/<${MMS}implies>*/^<$SUB>* <$MMS$target> }
    """, dataset).execAsk()
}

class AccessControlDefinitionsTests : StringSpec({
    val definitions = definitionsFrom(DEFINITIONS_TTL)
    val scopes = listOf("Scope", "Cluster", "Org", "Project", "Repo", "Collection", "Ref", "Branch", "Lock")

    "scope types match the SPARQL property path for every pair" {
        for(target in scopes) {
            val expected = scopes.filter { pathHolds(it, target) }.map { "$MMS$it" }
            definitions.scopeTypesFor(target) shouldContainExactlyInAnyOrder expected
        }
    }

    "a policy on the cluster covers a branch through mms:implies" {
        (("${MMS}Cluster") in definitions.scopeTypesFor("Branch")).shouldBeTrue()
        (("${MMS}Org") in definitions.scopeTypesFor("Repo")).shouldBeTrue()
    }

    "a scope type unknown to the definitions only covers itself" {
        definitions.scopeTypesFor("Artifact") shouldBe listOf("${MMS}Artifact")
    }

    "roles granting a permission follow mms:implies and require typed roles and permissions" {
        definitions.rolesGranting("ReadRepo") shouldContainExactlyInAnyOrder listOf(
            "${OBJ}Role.ReadRepo", "${OBJ}Role.WriteRepo", "${OBJ}Role.AdminCluster",
        )
        definitions.rolesGranting("UpdateBranch") shouldContainExactlyInAnyOrder listOf("${OBJ}Role.WriteRepo")
        definitions.rolesGranting("DeleteCluster").shouldBeEmpty()
    }

    "results are stable when requested again" {
        definitions.scopeTypesFor("Repo") shouldBe definitions.scopeTypesFor("Repo")
        definitions.rolesGranting("ReadRepo") shouldBe definitions.rolesGranting("ReadRepo")
    }

    "empty definitions are reported as empty and grant nothing" {
        val empty = AccessControlDefinitions(emptyMap(), emptyMap(), emptyMap(), emptyMap())
        empty.isEmpty.shouldBeTrue()
        empty.rolesGranting("ReadRepo").shouldBeEmpty()
        definitions.isEmpty.shouldBeFalse()
    }
})

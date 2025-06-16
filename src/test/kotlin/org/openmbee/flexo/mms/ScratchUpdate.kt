package org.openmbee.flexo.mms

import io.kotest.core.test.TestCase
import io.ktor.http.*
import org.apache.jena.rdf.model.ResourceFactory
import org.openmbee.flexo.mms.util.*

class ScratchUpdate: ScratchAny() {
    // create a scratch before each test
    override suspend fun beforeEach(testCase: TestCase) {
        super.beforeEach(testCase)
        createScratch(demoScratchPath, demoScratchName)
    }
    init {
        "insert data" {
            val updateBody = mutableListOf(insertAliceRex, insertBobFluffy).joinToString(";\n")
            withTest {
                httpPost("$demoScratchPath/update") {
                    setSparqlUpdateBody(updateBody)
                }
                httpGet("$demoScratchPath/graph") {}.apply {
                    response.exclusivelyHasTriples {
                        val people = demoPrefixes.get("")
                        subject("${people}Alice") {
                            ignoreAll()
                        }
                        subject("${people}Rex") {
                            ignoreAll()
                        }
                        subject("${people}Bob") {
                            ignoreAll()
                        }
                        subject("${people}Fluffy") {
                            ignoreAll()
                        }
                    }
                }
            }

        }

        "update scratch with various operations" {
            // resulting graph should have:
            // <urn:that> <urn:gets> <urn:reinserted>
            // <urn:these> <urn:more> <urn:inserts>
            val updateBody = mutableListOf(
                """
                    insert data {
                        <urn:this> <urn:is> <urn:inserted> .
                        <urn:that> <urn:willbe> <urn:deleted> .
                    }
                """.trimIndent(),
                """
                    delete where {
                        <urn:that> ?p ?o .
                    }
                """.trimIndent(),
                """
                    delete data {
                        <urn:does> <urn:not> <urn:exist> .
                    }
                """.trimIndent(),
                """
                    insert data {             
                         <urn:that> <urn:gets> <urn:reinserted>
                    }                   
                """.trimIndent(),
                """
                    delete {
                        ?s ?p ?o .
                    } insert {
                        <urn:these> <urn:more> <urn:inserts>
                    } where {
                        optional {
                            ?s ?p ?o .
                        }
                        values ?s { <urn:this> }
                    }
                """.trimIndent()
            ).joinToString(";\n")
            withTest {
                httpPost("$demoScratchPath/update") {
                    setSparqlUpdateBody(updateBody)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                }
                httpGet("$demoScratchPath/graph") {}.apply {
                    response.exclusivelyHasTriples {
                        subject("urn:these") {
                            exclusivelyHas(ResourceFactory.createProperty("urn:more") exactly ResourceFactory.createResource("urn:inserts"))
                        }
                        subject("urn:that") {
                            exclusivelyHas(ResourceFactory.createProperty("urn:gets") exactly ResourceFactory.createResource("urn:reinserted"))
                        }
                    }
                }
            }
        }

        "update on non-empty scratch" {
            updateScratch(demoScratchPath, insertAliceRex)
            val updateBody = """
                insert data {
                    <urn:this> <urn:is> <urn:inserted> .
                }
            """.trimIndent()
            withTest {
                httpPost("$demoScratchPath/update") {
                    setSparqlUpdateBody(updateBody)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                }
                httpGet("$demoScratchPath/graph") {}.apply {
                    response.includesTriples {
                        subject("urn:this") {
                            exclusivelyHas(ResourceFactory.createProperty("urn:is") exactly ResourceFactory.createResource("urn:inserted"))
                        }
                    }
                }
            }
        }

        "where optional clause doesn't match" {
            val updateBody = """
                delete {
                    ?s ?p ?o .
                } where {
                    optional {
                        ?s ?p ?o .
                    }
                    values ?s { <urn:this> }
                };
                insert data {
                    <urn:this> <urn:is> <urn:inserted> .
                }
            """.trimIndent()
            withTest {
                httpPost("$demoScratchPath/update") {
                    setSparqlUpdateBody(updateBody)
                }.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                }
                httpGet("$demoScratchPath/graph") {}.apply {
                    response.includesTriples {
                        subject("urn:this") {
                            exclusivelyHas(ResourceFactory.createProperty("urn:is") exactly ResourceFactory.createResource("urn:inserted"))
                        }
                    }
                }
            }
        }
    }
}

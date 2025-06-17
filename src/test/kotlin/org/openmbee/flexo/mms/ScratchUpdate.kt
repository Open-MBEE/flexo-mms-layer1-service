package org.openmbee.flexo.mms

import io.kotest.assertions.ktor.client.shouldHaveStatus
import io.kotest.core.test.TestCase
import io.ktor.http.*
import io.ktor.server.testing.*
import org.apache.jena.rdf.model.ResourceFactory
import org.openmbee.flexo.mms.util.*

class ScratchUpdate: ScratchAny() {
    // create a scratch before each test
    override suspend fun beforeEach(testCase: TestCase) {
        super.beforeEach(testCase)
        testApplication {
            createScratch(demoScratchPath, demoScratchName)
        }
    }
    init {
        "insert data" {
            val updateBody = mutableListOf(insertAliceRex, insertBobFluffy).joinToString(";\n")
            testApplication {
                httpPost("$demoScratchPath/update") {
                    setSparqlUpdateBody(updateBody)
                }
                httpGet("$demoScratchPath/graph") {}.apply {
                    this.exclusivelyHasTriples {
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
            testApplication {
                httpPost("$demoScratchPath/update") {
                    setSparqlUpdateBody(updateBody)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                }
                httpGet("$demoScratchPath/graph") {}.apply {
                    this.exclusivelyHasTriples {
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
            val updateBody = """
                insert data {
                    <urn:this> <urn:is> <urn:inserted> .
                }
            """.trimIndent()
            testApplication {
                updateScratch(demoScratchPath, insertAliceRex)
                httpPost("$demoScratchPath/update") {
                    setSparqlUpdateBody(updateBody)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                }
                httpGet("$demoScratchPath/graph") {}.apply {
                    this.includesTriples {
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
            testApplication {
                httpPost("$demoScratchPath/update") {
                    setSparqlUpdateBody(updateBody)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                }
                httpGet("$demoScratchPath/graph") {}.apply {
                    this.includesTriples {
                        subject("urn:this") {
                            exclusivelyHas(ResourceFactory.createProperty("urn:is") exactly ResourceFactory.createResource("urn:inserted"))
                        }
                    }
                }
            }
        }
    }
}

package org.openmbee.flexo.mms

import io.ktor.http.*
import org.openmbee.flexo.mms.util.LinkedDataPlatformDirectContainerTests
import org.openmbee.flexo.mms.util.createGroup
import org.openmbee.flexo.mms.util.exclusivelyHasTriples
import org.openmbee.flexo.mms.util.shouldHaveStatus

class GroupLdpDc : GroupAny() {
    init {
        LinkedDataPlatformDirectContainerTests(
            basePath = basePathGroups,
            resourceId = demoGroupId,
            validBodyForCreate = validGroupBody,
            resourceCreator = { createGroup(demoGroupId, demoGroupTitle) }
        ) {
            create {response, slug ->
                validateCreatedGroupTriples(response, slug, demoGroupTitle)
            }

            postWithPrecondition {
                response shouldHaveStatus HttpStatusCode.BadRequest
            }

            read() {
                it.response exclusivelyHasTriples {
                    validateGroupTriples(it.response, demoGroupId, demoGroupTitle)
                }
            }
        }
    }
}
//
//    init {
//        "group id with slash".config(tags=setOf(NoAuth)) {
//            withTest {
//                httpPut("$groupPath/non-existant-path/foobar") {
//                    setTurtleBody(validGroupBody)
//                }.apply {
//                    response shouldHaveStatus HttpStatusCode.NotFound
//                }
//            }
//        }
//
//        "reject invalid group id".config(tags=setOf(NoAuth)) {
//            withTest {
//                httpPut("$groupPath with invalid id") {
//                    setTurtleBody(validGroupBody)
//                }.apply {
//                    response shouldHaveStatus HttpStatusCode.BadRequest
//                }
//            }
//        }
//
//        mapOf(
//            "rdf:type" to "mms:NotGroup",
//            "mms:id" to "\"not-$groupId\"",
//        ).forEach { (pred, obj) ->
//            "reject wrong $pred".config(tags=setOf(NoAuth)) {
//                withTest {
//                    httpPut(groupPath) {
//                        setTurtleBody("""
//                            $validGroupBody
//                            <> $pred $obj .
//                        """.trimIndent())
//                    }.apply {
//                        response shouldHaveStatus HttpStatusCode.BadRequest
//                    }
//                }
//            }
//        }
//
//        "create valid group".config(tags=setOf(NoAuth)) {
//            withTest {
//                httpPut(groupPath) {
//                    setTurtleBody(validGroupBody)
//                }.apply {
//                    withClue("Checking that ETag is present in response") {
//                        response.headers[HttpHeaders.ETag].shouldNotBeBlank()
//                    }
//
//                    response includesTriples  {
//                        modelName = it
//
//                        validateCreatedGroupTriples(response, groupId, listOf(
//                            DCTerms.title exactly groupTitle.en
//                        ))
//                    }
//                }
//            }
//        }
//    }
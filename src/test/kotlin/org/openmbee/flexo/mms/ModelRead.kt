import io.kotest.assertions.ktor.client.shouldHaveStatus
import io.kotest.matchers.shouldBe
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.openmbee.flexo.mms.ModelAny
import org.openmbee.flexo.mms.util.*

class ModelRead : ModelAny() {
    init {
        listOf(
            "head",
            "get"
        ).forEach { method ->
            "$method non-existent model graph" {
                testApplication {
                    httpRequest(HttpMethod(method.uppercase()), "$demoLockPath/graph") {
                    }.apply {
                        this shouldHaveStatus HttpStatusCode.NotFound
                    }
                }
            }
        }

        "head branch graph" {
            testApplication {
                loadModel(masterBranchPath, loadAliceRex)
                httpHead("$masterBranchPath/graph") {}.apply {
                    this shouldHaveStatus HttpStatusCode.OK
//                    response.content shouldBe null
                }
            }
        }

        "get branch graph" {
            testApplication {
                loadModel(masterBranchPath, loadAliceRex)
                httpGet("$masterBranchPath/graph") {}.apply {
                    this shouldHaveStatus HttpStatusCode.OK

                    this.exclusivelyHasTriples {
                        subjectTerse(":Alice") {
                            ignoreAll()
                        }

                        subjectTerse(":Rex") {
                            ignoreAll()
                        }
                    }
                }
            }
        }

        "head lock graph" {
            testApplication {
                loadModel(masterBranchPath, loadAliceRex)
                createLock(demoRepoPath, masterBranchPath, demoLockId)
                httpHead("$demoLockPath/graph") {}.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this.bodyAsText() shouldBe ""
                }
            }
        }

        "get lock graph" {
            testApplication {
                loadModel(masterBranchPath, loadAliceRex)
                createLock(demoRepoPath, masterBranchPath, demoLockId)
                httpGet("$demoLockPath/graph") {}.apply {
                    this shouldHaveStatus HttpStatusCode.OK

                    this.exclusivelyHasTriples {
                        subjectTerse(":Alice") {
                            ignoreAll()
                        }

                        subjectTerse(":Rex") {
                            ignoreAll()
                        }
                    }
                }
            }
        }
    }
}

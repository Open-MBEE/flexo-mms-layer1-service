import io.kotest.matchers.shouldBe
import io.ktor.http.*
import org.openmbee.flexo.mms.ModelAny
import org.openmbee.flexo.mms.util.*

class ModelRead : ModelAny() {
    init {
        listOf(
            "head",
            "get"
        ).forEach { method ->
            "$method non-existent model graph" {
                withTest {
                    httpRequest(HttpMethod(method.uppercase()), "$demoLockPath/graph") {
                    }.apply {
                        response shouldHaveStatus HttpStatusCode.NotFound
                    }
                }
            }
        }

        "head branch graph" {
            loadModel(masterBranchPath, loadAliceRex)

            withTest {
                httpHead("$masterBranchPath/graph") {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK
//                    response.content shouldBe null
                }
            }
        }

        "get branch graph" {
            loadModel(masterBranchPath, loadAliceRex)

            withTest {
                httpGet("$masterBranchPath/graph") {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK

                    response.exclusivelyHasTriples {
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
            loadModel(masterBranchPath, loadAliceRex)
            createLock(demoRepoPath, masterBranchPath, demoLockId)

            withTest {
                httpHead("$demoLockPath/graph") {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                    response.content shouldBe null
                }
            }
        }

        "get lock graph" {
            loadModel(masterBranchPath, loadAliceRex)
            createLock(demoRepoPath, masterBranchPath, demoLockId)

            withTest {
                httpGet("$demoLockPath/graph") {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK

                    response.exclusivelyHasTriples {
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

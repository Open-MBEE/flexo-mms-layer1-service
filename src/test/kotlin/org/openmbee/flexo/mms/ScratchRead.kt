import io.kotest.assertions.ktor.client.shouldHaveStatus
import io.kotest.core.test.TestCase
import io.ktor.http.*
import io.ktor.server.testing.*
import org.openmbee.flexo.mms.ScratchAny
import org.openmbee.flexo.mms.util.*

class ScratchRead : ScratchAny() {
    // create a scratch before each test
    override suspend fun beforeEach(testCase: TestCase) {
        super.beforeEach(testCase)
        testApplication {
            createScratch(demoScratchPath, demoScratchName)
        }
    }

    init {
        "head scratch" {
            testApplication {
                loadScratch(demoScratchPath, loadAliceRex)
                httpHead("$demoScratchPath/graph") {}.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                }
            }
        }

        "get scratch" {
            testApplication {
                loadScratch(demoScratchPath, loadAliceRex)
                httpGet("$demoScratchPath/graph") {}.apply {
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

import io.kotest.core.test.TestCase
import io.ktor.http.*
import org.openmbee.flexo.mms.ScratchAny
import org.openmbee.flexo.mms.util.*

class ScratchRead : ScratchAny() {
    // create a scratch before each test
    override suspend fun beforeEach(testCase: TestCase) {
        super.beforeEach(testCase)
        createScratch(demoScratchPath, demoScratchName)
    }

    init {
        "head scratch" {
            loadScratch(demoScratchPath, loadAliceRex)

            withTest {
                httpHead("$demoScratchPath/graph") {}.apply {
                    response shouldHaveStatus HttpStatusCode.OK
                }
            }
        }

        "get scratch" {
            loadScratch(demoScratchPath, loadAliceRex)

            withTest {
                httpGet("$demoScratchPath/graph") {}.apply {
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

package org.openmbee.flexo.mms

import io.kotest.assertions.ktor.client.shouldHaveStatus
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.openmbee.flexo.mms.util.*
import org.slf4j.LoggerFactory


class CollectionQueryTest : CollectionAny() {
    override val logger = LoggerFactory.getLogger(CollectionQueryTest::class.java)
    val masterBranchPath = "$demoRepoPath/branches/master"
    // a second repo for cross-repo collection tests
    val secondRepoId = "second-repo"
    val secondRepoName = "Second Repo"
    val secondRepoPath = "$demoOrgPath/repos/$secondRepoId"
    val secondMasterBranchPath = "$secondRepoPath/branches/master"
    val secondLockId = "lock-1"
    val secondLockPath = "$secondRepoPath/locks/$secondLockId"

    val collectionLockId = "demo-lock"
    val collectionLockPath = "$demoRepoPath/locks/$collectionLockId"

    val scratchId = "scratch-1"
    val scratchPath = "$demoRepoPath/scratches/$scratchId"

    // insert Alice into first repo's master branch
    val insertAlice = """
        $demoPrefixesStr

        insert data {
            :Alice a :Person ;
                foaf:name "Alice" ;
                .
        }
    """.trimIndent()

    // insert Bob into second repo's master branch
    val insertBob = """
        $demoPrefixesStr

        insert data {
            :Bob a :Person ;
                foaf:name "Bob" ;
                .
        }
    """.trimIndent()

    // insert Charlie into scratch
    val insertCharlie = """
        $demoPrefixesStr

        insert data {
            :Charlie a :Person ;
                foaf:name "Charlie" ;
                .
        }
    """.trimIndent()

    // query to select all person names, ordered
    val queryPersonNames = """
        $demoPrefixesStr

        select ?name where {
            ?s a :Person .
            ?s foaf:name ?name .
        } order by asc(?name)
    """.trimIndent()

    // ASK query for Alice
    val askAlice = """
        $demoPrefixesStr

        ask {
            :Alice a :Person .
        }
    """.trimIndent()

    // CONSTRUCT query for all persons
    val constructPersons = """
        $demoPrefixesStr

        construct {
            ?s a :Person ;
                foaf:name ?name .
        } where {
            ?s a :Person ;
                foaf:name ?name .
        }
    """.trimIndent()

    init {
        "POST $demoCollectionPath/query - non-existent collection returns 404" {
            testApplication {
                httpPost("$demoCollectionPath/query") {
                    setSparqlQueryBody(queryPersonNames)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.NotFound
                }
            }
        }

        "POST $demoCollectionPath/query - query across branch and lock returns union" {
            testApplication {
                // insert Alice into first repo's master branch
                commitModel(masterBranchPath, insertAlice)

                // create second repo, insert Bob, then lock it
                createRepo(demoOrgPath, secondRepoId, secondRepoName)
                commitModel(secondMasterBranchPath, insertBob)
                createLock(secondRepoPath, secondMasterBranchPath, secondLockId)

                // create collection that collects the first repo's master branch and second repo's lock
                val collectionBody = withAllTestPrefixes("""
                    <> dct:title "$demoCollectionName"@en .
                    <> mms:collects <$demoBranchRef> .
                    <> mms:collects <$secondLockPath> .
                """.trimIndent())
                createCollection(demoOrgPath, demoCollectionId, collectionBody)

                // query the collection — should return union of Alice (branch) + Bob (lock)
                httpPost("$demoCollectionPath/query") {
                    setSparqlQueryBody(queryPersonNames)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this equalsSparqlResults {
                        binding(
                            "name" to "Alice".bindingLit
                        )
                        binding(
                            "name" to "Bob".bindingLit
                        )
                    }
                }
            }
        }

        "POST $demoCollectionPath/query - query across branch and scratch returns union" {
            testApplication {
                // insert Alice into the master branch
                commitModel(masterBranchPath, insertAlice)

                // create scratch and insert Charlie
                createScratch(scratchPath, "Scratch 1")
                updateScratch(scratchPath, insertCharlie)

                // create collection collecting the branch and scratch
                val collectionBody = withAllTestPrefixes("""
                    <> dct:title "$demoCollectionName"@en .
                    <> mms:collects <$demoBranchRef> .
                    <> mms:collects <$scratchPath> .
                """.trimIndent())
                createCollection(demoOrgPath, demoCollectionId, collectionBody)

                // query — should return Alice (branch) + Charlie (scratch)
                httpPost("$demoCollectionPath/query") {
                    setSparqlQueryBody(queryPersonNames)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this equalsSparqlResults {
                        binding(
                            "name" to "Alice".bindingLit
                        )
                        binding(
                            "name" to "Charlie".bindingLit
                        )
                    }
                }
            }
        }

        "POST $demoCollectionPath/query - query across branch, lock, and scratch returns full union" {
            testApplication {
                // insert Alice into first repo's master branch
                commitModel(masterBranchPath, insertAlice)

                // create second repo, insert Bob, lock it
                createRepo(demoOrgPath, secondRepoId, secondRepoName)
                commitModel(secondMasterBranchPath, insertBob)
                createLock(secondRepoPath, secondMasterBranchPath, secondLockId)

                // create scratch and insert Charlie
                createScratch(scratchPath, "Scratch 1")
                updateScratch(scratchPath, insertCharlie)

                // create collection collecting all three ref types
                val collectionBody = withAllTestPrefixes("""
                    <> dct:title "$demoCollectionName"@en .
                    <> mms:collects <$demoBranchRef> .
                    <> mms:collects <$secondLockPath> .
                    <> mms:collects <$scratchPath> .
                """.trimIndent())
                createCollection(demoOrgPath, demoCollectionId, collectionBody)

                // query — should return Alice + Bob + Charlie
                httpPost("$demoCollectionPath/query") {
                    setSparqlQueryBody(queryPersonNames)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this equalsSparqlResults {
                        binding(
                            "name" to "Alice".bindingLit
                        )
                        binding(
                            "name" to "Bob".bindingLit
                        )
                        binding(
                            "name" to "Charlie".bindingLit
                        )
                    }
                }
            }
        }

        "POST $demoCollectionPath/query - ASK query returns true when data exists" {
            testApplication {
                // insert Alice into the master branch
                commitModel(masterBranchPath, insertAlice)

                // create collection collecting the branch
                httpPut(demoCollectionPath) {
                    setTurtleBody(withAllTestPrefixes(validCollectionBody))
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Created
                }

                // ASK for Alice — should be true
                httpPost("$demoCollectionPath/query") {
                    setSparqlQueryBody(askAlice)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this shouldEqualSparqlResultsJson """
                        {
                            "head": {},
                            "boolean": true
                        }
                    """.trimIndent()
                }
            }
        }

        "POST $demoCollectionPath/query - ASK query returns false when no matching data" {
            testApplication {
                // insert Bob (not Alice) into the master branch
                commitModel(masterBranchPath, insertBob)

                // create collection
                httpPut(demoCollectionPath) {
                    setTurtleBody(withAllTestPrefixes(validCollectionBody))
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Created
                }

                // ASK for Alice — should be false
                httpPost("$demoCollectionPath/query") {
                    setSparqlQueryBody(askAlice)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this shouldEqualSparqlResultsJson """
                        {
                            "head": {},
                            "boolean": false
                        }
                    """.trimIndent()
                }
            }
        }

        "POST $demoCollectionPath/query - CONSTRUCT returns union triples" {
            testApplication {
                // insert Alice into master branch
                commitModel(masterBranchPath, insertAlice)

                // create second repo, insert Bob
                createRepo(demoOrgPath, secondRepoId, secondRepoName)
                commitModel(secondMasterBranchPath, insertBob)

                // create collection collecting both branches
                val collectionBody = withAllTestPrefixes("""
                    <> dct:title "$demoCollectionName"@en .
                    <> mms:collects <$demoBranchRef> .
                    <> mms:collects <$secondMasterBranchPath> .
                """.trimIndent())
                createCollection(demoOrgPath, demoCollectionId, collectionBody)

                // CONSTRUCT — should return triples for both Alice and Bob
                httpPost("$demoCollectionPath/query") {
                    setSparqlQueryBody(constructPersons)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this includesTriples {
                        subjectTerse(":Alice") {
                            ignoreAll()
                        }
                        subjectTerse(":Bob") {
                            ignoreAll()
                        }
                    }
                }
            }
        }

        "POST $demoCollectionPath/query - FROM clause rejected with 403" {
            testApplication {
                // create collection
                httpPut(demoCollectionPath) {
                    setTurtleBody(withAllTestPrefixes(validCollectionBody))
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Created
                }

                // query with FROM clause — should be rejected
                httpPost("$demoCollectionPath/query") {
                    setSparqlQueryBody(withAllTestPrefixes("""
                        select *
                            from m-graph:AccessControl.Policies
                        {
                            ?s ?p ?o
                        }
                    """.trimIndent()))
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Forbidden
                }
            }
        }

        "POST $demoCollectionPath/query - FROM NAMED clause rejected with 403" {
            testApplication {
                // create collection
                httpPut(demoCollectionPath) {
                    setTurtleBody(withAllTestPrefixes(validCollectionBody))
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Created
                }

                // query with FROM NAMED clause — should be rejected
                httpPost("$demoCollectionPath/query") {
                    setSparqlQueryBody(withAllTestPrefixes("""
                        select *
                            from named m-graph:AccessControl.Policies
                        {
                            graph ?g {
                                ?s ?p ?o
                            }
                        }
                    """.trimIndent()))
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Forbidden
                }
            }
        }

        "POST $demoCollectionPath/query/inspect - inspect generated query" {
            testApplication {
                // insert data and create collection
                commitModel(masterBranchPath, insertAlice)
                httpPut(demoCollectionPath) {
                    setTurtleBody(withAllTestPrefixes(validCollectionBody))
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Created
                }

                // inspect query — should return the rewritten query text, not execute it
                httpPost("$demoCollectionPath/query/inspect") {
                    setSparqlQueryBody(queryPersonNames)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                }
            }
        }

        "GET $demoCollectionPath/graph - get union graph across branch and lock" {
            testApplication {
                // insert Alice into first repo
                commitModel(masterBranchPath, insertAlice)

                // create second repo, insert Bob, lock it
                createRepo(demoOrgPath, secondRepoId, secondRepoName)
                commitModel(secondMasterBranchPath, insertBob)
                createLock(secondRepoPath, secondMasterBranchPath, secondLockId)

                // create collection collecting both
                val collectionBody = withAllTestPrefixes("""
                    <> dct:title "$demoCollectionName"@en .
                    <> mms:collects <$demoBranchRef> .
                    <> mms:collects <$secondLockPath> .
                """.trimIndent())
                createCollection(demoOrgPath, demoCollectionId, collectionBody)

                // GET the union graph — should contain triples from both
                httpGet("$demoCollectionPath/graph") {}.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this includesTriples {
                        subjectTerse(":Alice") {
                            ignoreAll()
                        }
                        subjectTerse(":Bob") {
                            ignoreAll()
                        }
                    }
                }
            }
        }

        "POST $demoCollectionPath/query - single branch collection returns only that branch's data" {
            testApplication {
                // insert Alice into master
                commitModel(masterBranchPath, insertAlice)

                // create collection with just the master branch
                httpPut(demoCollectionPath) {
                    setTurtleBody(withAllTestPrefixes(validCollectionBody))
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Created
                }

                // query — should return only Alice
                httpPost("$demoCollectionPath/query") {
                    setSparqlQueryBody(queryPersonNames)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this equalsSparqlResults {
                        binding(
                            "name" to "Alice".bindingLit
                        )
                    }
                }
            }
        }

        "POST $demoCollectionPath/query - lock preserves snapshot at time of locking" {
            testApplication {
                // insert Alice, then lock
                commitModel(masterBranchPath, insertAlice)
                createLock(demoRepoPath, masterBranchPath, collectionLockId)

                // insert Bob after locking (only on branch, not visible via lock)
                commitModel(masterBranchPath, insertBob)

                // create collection collecting only the lock
                val collectionBody = withAllTestPrefixes("""
                    <> dct:title "$demoCollectionName"@en .
                    <> mms:collects <$collectionLockPath> .
                """.trimIndent())
                createCollection(demoOrgPath, demoCollectionId, collectionBody)

                // query — lock should only see Alice (snapshot before Bob was added)
                httpPost("$demoCollectionPath/query") {
                    setSparqlQueryBody(queryPersonNames)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                    this equalsSparqlResults {
                        binding(
                            "name" to "Alice".bindingLit
                        )
                    }
                }
            }
        }
    }
}

package org.openmbee.mms5.routes

import io.ktor.application.*
import io.ktor.routing.*
import org.openmbee.mms5.*

private val DEFAULT_CONDITIONS =  LOCK_CRUD_CONDITIONS.append {
    permit(Permission.DELETE_LOCK, Scope.LOCK)
}

fun Application.deleteLock() {
    routing {
        delete("/orgs/{orgId}/repos/{repoId}/commit/{commitId}/locks/{lockId}") {
            call.mmsL1(Permission.DELETE_LOCK) {
                pathParams {
                    org()
                    repo()
                    commit()
                    lock()
                }

                val localConditions = DEFAULT_CONDITIONS

                buildSparqlUpdate {
                    compose {
                        txn()
                        conditions(localConditions)

                        raw(dropLock())

                        // delete {
                        //     raw("""
                        //         morcl: ?lock_p ?lock_o .
                        //     """)
                        // }
                        // where {
                        //     raw("""
                        //         graph mor-graph:Metadata {
                        //             morcl: ?lock_p ?lock_o .
                        //
                        //             optional {
                        //                 ?thing mms:ref morcl: ;
                        //                     ?thing_p ?thing_o .
                        //             }
                        //
                        //             filter not exists {
                        //                 ?dangle ?dangle_p morcl: .
                        //
                        //                 minus {
                        //                     ?thing ?thing_p ?thing_o .
                        //                 }
                        //             }
                        //         }
                        //     """)
                        // }
                    }
                    //
                    // // txn()
                    // // conditions(localConditions)
                    // // dropLock()
                    //
                    // delete {
                    //     raw("""
                    //         morcl: ?lock_p ?lock_o .
                    //     """)
                    // }
                    // insert {
                    //     txn()
                    //
                    // }
                    // where {
                    //     raw(*localConditions.requiredPatterns())
                    //
                    //     raw("""
                    //         graph mor-graph:Metadata {
                    //             morcl: ?lock_p ?lock_o .
                    //
                    //             optional {
                    //                 ?thing mms:ref morcl: ;
                    //                     ?thing_p ?thing_o .
                    //             }
                    //
                    //             filter not exists {
                    //                 ?dangle ?dangle_p morcl: .
                    //
                    //                 minus {
                    //                     ?thing ?thing_p ?thing_o .
                    //                 }
                    //             }
                    //         }
                    //     """)
                    // }
                }
            }
        }
    }
}

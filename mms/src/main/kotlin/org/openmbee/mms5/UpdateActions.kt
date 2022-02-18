package org.openmbee.mms5

class ObjectUpdateAction(private val builder: ComposeUpdateBuilder, val name: String) {
    var whereStringBuilder = StringBuilder()

    fun where(append: String) {
        whereStringBuilder.append(append)
    }

    fun anyDanglingNodes() {
        where("""
            filter not exists {
                ?diff_dangle ?diff_dangle_p ?diff .
            }
        """)
    }

    fun dependent(path: String, dep: String) {
        where("""
            optional {
                $dep $path ?$name ;
                    ${dep}_p ${dep}_o .
            }
        """)
    }
}

private fun ComposeUpdateBuilder.dropRepoObject(name: String, setup: ObjectUpdateAction.() -> Unit): String {
    deleteString += """
        graph mor-graph:Metadata {
            ?${name} ?${name}_p ?${name}_o .
        }
    """

    insertString += """
        graph m-graph:Transactions {
            mt: mms-txn:droppedObject ?${name} .
        }
    """

    whereString += ObjectUpdateAction(this, name).run {
        setup()
        """
            graph mor-graph:Metadata {
                ?${name} ?${name}_p ?${name}_o .
            
                $whereStringBuilder
            }
        """
    }

    return "?$name"
}

fun ComposeUpdateBuilder.dropDiff(): String {
    return dropRepoObject("diff") {
        anyDanglingNodes()
    }
}

fun ComposeUpdateBuilder.dropLock(): String {
    return dropRepoObject("lock") {
        // TODO update to use ^mms:snapshot instead
        dependent("mms:ref", dropDiff())
    }
    //
    // pendingDeleteString += """
    //     ?lock ?lock_p ?lock_o .
    // """
    //
    // pendingInsertString += """
    //     graph m-graph:Transactions {
    //         mt: mms-txn:droppedObject ?lock .
    //     }
    // """
    //
    // pendingWhereString += """
    //     graph mor-graph:Metadata {
    //         ?lock ?lock_p ?lock_o .
    //
    //         optional {
    //             ?diff mms:ref ?lock ;
    //                 ?diff_p ?diff_o .
    //         }
    //
    //         filter not exists {
    //             ?lock_dangle ?lock_dangle_p ?lock .
    //
    //             filter not exists {
    //                 ?diff mms:ref ?lock ;
    //                     ?lock_dangle_diff_p ?lock_dangle_diff_o .
    //             }
    //         }
    //     }
    // """
}

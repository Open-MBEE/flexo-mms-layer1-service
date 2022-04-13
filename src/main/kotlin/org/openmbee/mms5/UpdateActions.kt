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

    fun dependency(path: String, dep: String, condition: String) {
        where("""
            
            ?$name $path $dep .
            
            $dep ${dep}_p ${dep}_o .
        """)
    }
}

private fun ComposeUpdateBuilder.dropRepoObject(name: String, setup: (ObjectUpdateAction.() -> Unit)?=null): String {
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
        setup?.let { it() }
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

fun ComposeUpdateBuilder.dropSnapshot(): String {
    return dropRepoObject("snapshot") {
        dependent("mms:ref", dropSnapshot())
    }
}

fun ComposeUpdateBuilder.dropLock(): String {
    return dropRepoObject("lock") {
        dropRepoObject("snapshot")

        // delete snapshot dependencies
        where("""
            # delete dangling snapshots
            optional {
                ?lock mms:snapshot ?snapshot .
                
                ?snapshot ?snapshot_p ?snapshot_o .
                
                # only if no other locks exist for that snapshot
                filter not exists {
                    ?otherLock mms:snapshot ?snapshot .
                    
                    filter(?otherLock != ?lock)
                }
                
                # drop snapshot graph
                ?snapshot mms:graph ?dropGraph .
                graph ?dropGraph {
                    ?drop_s ?drop_p ?drop_o .
                }
            }
        """)
    }
}

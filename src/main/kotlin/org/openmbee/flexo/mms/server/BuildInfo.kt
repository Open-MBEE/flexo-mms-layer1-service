package org.openmbee.flexo.mms.server

import java.util.*

private const val CONFIG = "build-info.properties"
object BuildInfo {
    private val properties = Properties()
    init {
        val file = this::class.java.classLoader.getResourceAsStream(CONFIG)
        if (file != null) properties.load(file)
    }
    fun getProperty(key: String): String = properties.getProperty(key)
}

package com.oleg.androidagent

import java.util.Locale

object KeyboardSearchPolicy {
    fun accepts(defaultPackage: String, windowPackage: String, nodePackage: String, label: String, viewId: String): Boolean =
        defaultPackage.isNotBlank() && windowPackage == defaultPackage && nodePackage == defaultPackage &&
            viewId == "$defaultPackage:id/key_pos_ime_action" &&
            label.trim().lowercase(Locale.ROOT) in setOf("search", "поиск", "искать", "іздеу")
}

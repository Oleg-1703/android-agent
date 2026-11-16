package com.oleg.androidagent

import org.junit.Assert.*
import org.junit.Test

class KeyboardSearchPolicyTest {
    private val keyboard = "com.google.android.inputmethod.latin"
    private val actionId = "$keyboard:id/key_pos_ime_action"
    @Test fun onlySelectedKeyboardCanProvideSearch() {
        assertTrue(KeyboardSearchPolicy.accepts(keyboard, keyboard, keyboard, "Search", actionId))
        assertFalse(KeyboardSearchPolicy.accepts(keyboard, "other.app", keyboard, "Search", actionId))
        assertFalse(KeyboardSearchPolicy.accepts(keyboard, keyboard, "other.app", "Search", actionId))
    }
    @Test fun blankKeyboardCannotGrantAccess() { assertFalse(KeyboardSearchPolicy.accepts("", "", "", "Search", actionId)) }
    @Test fun sendAndOtherActionsAreRejected() {
        for (label in listOf("Send", "Отправить", "Done", "Search Google", "Banana"))
            assertFalse(KeyboardSearchPolicy.accepts(keyboard, keyboard, keyboard, label, actionId))
    }
    @Test fun supportedSearchLabelsAreAccepted() {
        for (label in listOf("SEARCH", "Поиск", "Искать", "Іздеу"))
            assertTrue(KeyboardSearchPolicy.accepts(keyboard, keyboard, keyboard, label, actionId))
    }
    @Test fun toolbarSearchCannotReplaceTheImeActionKey() {
        for (id in listOf("", "$keyboard:id/toolbar_search", "other.app:id/key_pos_ime_action"))
            assertFalse(KeyboardSearchPolicy.accepts(keyboard, keyboard, keyboard, "Search", id))
    }
}

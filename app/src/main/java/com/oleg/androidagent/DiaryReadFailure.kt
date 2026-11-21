package com.oleg.androidagent

/** A bounded scan ended incomplete; do not repeat the same pre-write UI traversal. */
class DiaryReadFailure(message: String) : IllegalArgumentException(message)

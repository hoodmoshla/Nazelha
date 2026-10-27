package com.snapnet.util

/**
 * Moves a sensitive task-queue payload from the legacy preferences file to its dedicated store.
 * The old copy is removed only after a valid current payload exists or the new write succeeds.
 */
internal fun migrateTaskListPayload(
    currentPayload: String?,
    legacyPayload: String?,
    persistCurrent: (String) -> Boolean,
    clearLegacy: () -> Unit,
): String? {
    if (currentPayload != null) {
        clearLegacy()
        return currentPayload
    }
    if (legacyPayload != null && persistCurrent(legacyPayload)) clearLegacy()
    return legacyPayload
}

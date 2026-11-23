package com.oleg.androidagent

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/** Pure editing of Settings.Secure enabled_accessibility_services (colon-separated flattened component names). */
object AccessibilityList {
    private fun entries(current: String?) = current.orEmpty().split(':').map { it.trim() }.filter { it.isNotEmpty() }
    /** Settings may store "pkg/.Cls" or "pkg/pkg.Cls"; both name the same component. */
    fun same(entry: String, pkg: String, cls: String): Boolean {
        val pair = entry.split('/', limit = 2)
        if (pair.size != 2 || pair[0] != pkg) return false
        val name = if (pair[1].startsWith(".")) pkg + pair[1] else pair[1]
        return name == cls
    }
    fun contains(current: String?, pkg: String, cls: String) = entries(current).any { same(it, pkg, cls) }
    /** Only our component is added; every other enabled service is kept in order. */
    fun with(current: String?, pkg: String, cls: String): String =
        (entries(current).filterNot { same(it, pkg, cls) } + "$pkg/$cls").joinToString(":")
    fun without(current: String?, pkg: String, cls: String): String =
        entries(current).filterNot { same(it, pkg, cls) }.joinToString(":")
}

/**
 * Detects "Accessibility service is off", optionally re-enables it, and tells the owner once per outage.
 * Android switches the service off after an app update, force stop or crash; some OEM battery managers do too.
 */
class AccessibilityGuard(private val store: StateStore, private val running: () -> Boolean, private val tryEnable: () -> Boolean,
    private val notify: (String) -> Unit, private val now: () -> Long = { System.currentTimeMillis() },
    private val settle: () -> Unit = { Thread.sleep(400) }) {
    private var lastAttempt: Long? = null
    /** True when the service is running (possibly after re-enabling it). Never throws. */
    fun ensure(): Boolean {
        if (running()) {
            if (store.get(OUTAGE_KEY).isNotBlank()) store.put(OUTAGE_KEY, "")
            return true
        }
        val time = now()
        if (lastAttempt.let { it == null || time - it >= RETRY_MILLIS }) {
            lastAttempt = time
            val enabled = try { tryEnable() } catch (_: Exception) { false }
            if (enabled) for (i in 0 until 10) {
                settle()
                if (running()) {
                    if (store.get(OUTAGE_KEY).isNotBlank()) store.put(OUTAGE_KEY, "")
                    return true
                }
            }
        }
        if (store.get(OUTAGE_KEY).isBlank()) {
            try {
                notify(MESSAGE)
                store.put(OUTAGE_KEY, time.toString())
            } catch (_: Exception) { /* A lost message is retried at the next check; it must not stop the agent. */ }
        }
        return false
    }
    companion object {
        const val OUTAGE_KEY = "a11y_outage"
        const val RETRY_MILLIS = 30_000L
        const val MESSAGE = "Служба специальных возможностей Android Agent выключена, поэтому записать еду в FatSecret сейчас нельзя. " +
            "Android выключает её после обновления приложения, остановки или сбоя.\n\n" +
            "Как включить: Настройки телефона - Специальные возможности - Установленные службы (или Загруженные приложения) - Android Agent - Включить. " +
            "Распознанная еда дождётся в очереди, повторно отправлять её не нужно. Это сообщение приходит один раз за каждое отключение.\n\n" +
            "Чтобы не включать вручную, один раз выдай приложению право через ADB: " +
            "adb shell pm grant com.oleg.androidagent android.permission.WRITE_SECURE_SETTINGS (необязательно, подробности в docs/INSTALL_RU.md)."
        @Volatile var current: AccessibilityGuard? = null

        /** Real Android pieces. Works only if WRITE_SECURE_SETTINGS was granted over ADB; otherwise returns false. */
        fun enableSelf(context: Context): Boolean {
            if (context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return false
            val pkg = context.packageName
            val cls = FatAccessibilityService::class.java.name
            val resolver = context.contentResolver
            val list = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            // Listed but not bound (killed process): remove and re-add so the system binds it again.
            if (AccessibilityList.contains(list, pkg, cls)) {
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, AccessibilityList.without(list, pkg, cls))
            }
            val updated = AccessibilityList.with(Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), pkg, cls)
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            return true
        }
        fun create(context: Context, store: StateStore, notify: (String) -> Unit) =
            AccessibilityGuard(store, { FatAccessibilityService.instance != null }, { enableSelf(context) }, notify)
    }
}

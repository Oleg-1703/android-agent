package com.oleg.androidagent

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager

/** Dependency-free API 30 smoke runner. Only run on a disposable test device. */
class DeviceSmoke : Instrumentation() {
    private var phase = "write"
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); phase=arguments?.getString("phase") ?: "write"; start() }
    override fun onStart() {
        val result=Bundle()
        try {
            val context=targetContext
            val store=SecretStore(context)
            val marker="device-smoke-not-a-secret"
            if(phase=="read") {
                check(store.get("device_smoke")==marker) { "Keystore decrypt after force-stop failed" }
                context.getSharedPreferences("agent_private",0).edit().remove("device_smoke").commit()
                result.putString("stream","PASS: encrypted state survives force-stop/process restart; test marker removed\n")
            } else {
                check(store.config().length()==0) { "Use a clean disposable emulator; existing user configuration found" }
                store.put("device_smoke",marker)
                val raw=context.getSharedPreferences("agent_private",0).getString("device_smoke",null)!!
                check(!raw.contains(marker) && raw.contains(':')) { "Plaintext state detected" }
                check(SecretStore(context).get("device_smoke")==marker)
                val activity=startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                waitForIdleSync()
                runOnMainSync { check(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) { "Setup screen not protected" } }
                context.startForegroundService(Intent(context,AgentService::class.java))
                val deadline=SystemClock.elapsedRealtime()+30000
                while(SystemClock.elapsedRealtime()<deadline && (AgentService.workerActive || !store.get("service_status").contains("stopped"))) SystemClock.sleep(500)
                check(!AgentService.workerActive) { "Unconfigured service kept polling" }
                check(store.get("service_status").contains("stopped")) { "Missing safe configuration failure" }
                runOnMainSync { activity.finish() }
                result.putString("stream","PASS: Activity launches on API ${android.os.Build.VERSION.SDK_INT}; FLAG_SECURE; encrypted Keystore round-trip; unconfigured foreground service stops safely\n")
            }
            finish(Activity.RESULT_OK,result)
        } catch(t:Throwable) {
            result.putString("stream","FAIL: ${t.javaClass.simpleName}: ${t.message}\n");finish(Activity.RESULT_CANCELED,result)
        }
    }
}

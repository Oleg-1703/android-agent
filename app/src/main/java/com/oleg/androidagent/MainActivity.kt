package com.oleg.androidagent

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 36, 24, 24)
        }
        layout.addView(TextView(this).apply {
            text = "Android Agent v0.1\nLocal inspection only; no remote control enabled."
            textSize = 18f
        })
        layout.addView(Button(this).apply {
            text = "Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        layout.addView(Button(this).apply {
            text = "Open FatSecret"
            setOnClickListener {
                val launch = packageManager.getLaunchIntentForPackage(FatAccessibilityService.FATSECRET_PACKAGE)
                if (launch != null) startActivity(launch)
                else Toast.makeText(this@MainActivity, "FatSecret not installed or not visible", Toast.LENGTH_LONG).show()
            }
        })
        layout.addView(Button(this).apply {
            text = "Check accessibility connection"
            setOnClickListener {
                val message = if (FatAccessibilityService.instance == null) "Service disconnected" else "Service connected"
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        })
        setContentView(layout)
    }
}

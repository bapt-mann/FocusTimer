package com.example.focustimer

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    /**
     * True quand l'appli tourne en build DEBUG (Run / Debug depuis Android Studio).
     * False en build signée Release.
     * Utilise FLAG_DEBUGGABLE plutôt que BuildConfig.DEBUG pour éviter d'avoir à
     * activer buildFeatures { buildConfig = true } dans build.gradle.kts.
     */
    private val isDebugBuild: Boolean
        get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Sécurité dev : si un ancien build release a désactivé MainActivity,
        // on se ré-active au cas où (no-op si déjà activé).
        if (isDebugBuild) {
            try {
                val componentName = ComponentName(this, MainActivity::class.java)
                packageManager.setComponentEnabledSetting(
                    componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            } catch (_: Exception) { /* no-op */ }
        }

        // 1. Demande de permission overlay (inchangé)
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        // 2. Bouton pour cacher l'application
        val hideButton = findViewById<Button>(R.id.hideAppButton)
        hideButton.setOnClickListener {
            hideAppIcon()
        }
    }

    private fun hideAppIcon() {
        // Commande qui supprime l'icône du téléphone
        val componentName = ComponentName(this, MainActivity::class.java)
        packageManager.setComponentEnabledSetting(
            componentName,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )

        Toast.makeText(this, "L'application est devenue un fantôme !", Toast.LENGTH_LONG).show()
        finish()
    }
}

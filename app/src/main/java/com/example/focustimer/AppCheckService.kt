package com.example.focustimer

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.TextView
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class AppCheckService : AccessibilityService() {

    private var overlayView: View? = null
    private var countDownTimer: CountDownTimer? = null
    private var isAppUnlocked = false
    private var lastPackageName: String = ""

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event?.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return
        }

        val rootNode = rootInActiveWindow ?: return
        val currentPackage = rootNode.packageName?.toString() ?: event.packageName?.toString() ?: return

        if (currentPackage == "com.example.focustimer") return

        var isTryingToDisableApp = false

        // 1. OPTIMISATION : On ne scanne les mots QUE sur les applis à "risque"
        // (Paramètres, Accueil Xiaomi, Accueil Android, Installeur d'applis)
        val dangerZones = listOf(
            "com.android.settings",
            "com.miui.home",
            "com.android.systemui",
            "com.google.android.packageinstaller"
        )

        if (dangerZones.contains(currentPackage)) {
            // 2. L'ANTI-CRASH (try/catch)
            try {
                val hasFocusTimer = rootNode.findAccessibilityNodeInfosByText("FocusTimer").isNotEmpty()
                val hasUtiliser = rootNode.findAccessibilityNodeInfosByText("Utiliser FocusTimer").isNotEmpty()
                val hasDesinstaller = rootNode.findAccessibilityNodeInfosByText("Désinstaller").isNotEmpty()
                val hasForcerArret = rootNode.findAccessibilityNodeInfosByText("Forcer l'arrêt").isNotEmpty()

                if (hasFocusTimer && hasUtiliser) isTryingToDisableApp = true
                if (hasFocusTimer && (hasDesinstaller || hasForcerArret)) isTryingToDisableApp = true
            } catch (e: Exception) {
                // Si l'écran est trop lourd à lire, on ignore l'erreur au lieu de crasher !
            }
        }

        val targetApps = listOf(
            "com.instagram.android",
            "com.zhiliaoapp.musically",
            "com.google.android.youtube"
        )

        if (targetApps.contains(currentPackage) || isTryingToDisableApp) {
            if (!isAppUnlocked && overlayView == null) {
                showTimerOverlay()
            }
        } else {
            isAppUnlocked = false
            removeOverlayAndCancelTimer()
        }
    }

    private fun showTimerOverlay() {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_layout, null)
        val timerText = overlayView?.findViewById<TextView>(R.id.timerTextView)

        val quitButton = overlayView?.findViewById<Button>(R.id.quitButton)
        quitButton?.setOnClickListener {
            // NOUVEAU 4 : On détruit l'overlay IMMÉDIATEMENT pour éviter le flash
            removeOverlayAndCancelTimer()
            // Puis on rentre à la maison
            performGlobalAction(GLOBAL_ACTION_HOME)
        }

        windowManager.addView(overlayView, params)

        countDownTimer = object : CountDownTimer(100000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                timerText?.text = "${millisUntilFinished / 1000}s..."
            }

            override fun onFinish() {
                // NOUVEAU 5 : Le temps est écoulé, on autorise la navigation libre
                isAppUnlocked = true
                removeOverlayAndCancelTimer()
            }
        }.start()
    }

    private fun removeOverlayAndCancelTimer() {
        countDownTimer?.cancel()

        if (overlayView != null) {
            try {
                val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
                windowManager.removeView(overlayView)
            } catch (e: Exception) {
                // Sécurité au cas où la vue serait déjà enlevée
            }
            overlayView = null
        }
    }

    override fun onInterrupt() {
        removeOverlayAndCancelTimer()
    }
    override fun onServiceConnected() {
        super.onServiceConnected()

        val channelId = "focustimer_channel"

        // 1. Création du canal de notification (Obligatoire sur les Android récents)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Bouclier FocusTimer",
                NotificationManager.IMPORTANCE_LOW // "Low" pour qu'elle ne sonne pas à chaque fois
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        // 2. Création de la notification permanente
        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("🛡️ FocusTimer est actif")
                .setContentText("Le bouclier anti-distraction te protège en fond.")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock) // Petite icône de cadenas d'Android
                .setOngoing(true) // LA MAGIE EST LÀ : Rend la notification impossible à balayer !
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("🛡️ FocusTimer est actif")
                .setContentText("Le bouclier anti-distraction te protège en fond.")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setOngoing(true)
                .build()
        }

        // 3. On lance le service en "Premier Plan" avec sa protection
        startForeground(1, notification)
    }
}
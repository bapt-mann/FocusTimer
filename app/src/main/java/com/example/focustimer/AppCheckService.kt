package com.example.focustimer

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.CountDownTimer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class AppCheckService : AccessibilityService() {

    // --- Etat courant ---
    private var overlayView: View? = null
    private var overlayMode: OverlayMode = OverlayMode.NONE
    private var countDownTimer: CountDownTimer? = null

    /** Package pour lequel le timer est en train de tourner. */
    private var timerPackage: String? = null

    /** Package pour lequel l'utilisateur a attendu les 100s et est donc libre. */
    private var unlockedPackage: String? = null

    // Distingue l'overlay "plein écran" (TikTok/YouTube)
    // de l'overlay "partiel" (Instagram, bottom nav libre).
    private enum class OverlayMode { NONE, TIMER_FULLSCREEN, INSTAGRAM_PARTIAL }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // On traite :
        //  - WINDOW_STATE_CHANGED / WINDOWS_CHANGED : changement d'écran/activity
        //  - VIEW_SELECTED : changement d'onglet Insta qui ne change pas l'activity
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event?.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            event?.eventType != AccessibilityEvent.TYPE_VIEW_SELECTED) {
            return
        }

        val rootNode = rootInActiveWindow ?: return
        val currentPackage = rootNode.packageName?.toString() ?: event.packageName?.toString() ?: return

        if (currentPackage == "com.example.focustimer") return

        // Si on change de package et qu'on avait un unlock valide pour l'ancien,
        // on l'invalide (chaque appli a son propre cooldown 100s).
        if (unlockedPackage != null && unlockedPackage != currentPackage) {
            unlockedPackage = null
        }

        // --- 1. Détection tentative de désactivation de l'appli ---
        var isTryingToDisableApp = false
        val dangerZones = listOf(
            "com.android.settings",
            "com.android.systemui",
            "com.google.android.packageinstaller"
        )
        if (dangerZones.contains(currentPackage)) {
            try {
                val hasFocusTimer = rootNode.findAccessibilityNodeInfosByText("FocusTimer").isNotEmpty()
                val hasUtiliser = rootNode.findAccessibilityNodeInfosByText("Utiliser FocusTimer").isNotEmpty()
                val hasDesinstaller = rootNode.findAccessibilityNodeInfosByText("Désinstaller").isNotEmpty()
                val hasForcerArret = rootNode.findAccessibilityNodeInfosByText("Forcer l'arrêt").isNotEmpty()

                if (hasFocusTimer && hasUtiliser) isTryingToDisableApp = true
                if (hasFocusTimer && (hasDesinstaller || hasForcerArret)) isTryingToDisableApp = true
            } catch (e: Exception) {
                // Ecran trop lourd à lire : on ignore plutôt que de crasher
            }
        }

        // --- 2. Cas Instagram : détection 3 états ---
        //   PROFILE  -> overlay caché + timer reset
        //   MESSAGES -> overlay gardé + timer reset (pas d'attente possible en DM)
        //   BLOCKED  -> overlay partiel + timer 100s qui tourne
        if (currentPackage == InstagramScreenDetector.PACKAGE) {
            if (unlockedPackage == currentPackage) {
                // Déjà unlocked (100s déjà attendus cette session) : accès libre
                hideOverlayOnly()
                return
            }

            when (InstagramScreenDetector.detect(event, rootNode)) {
                InstagramScreenDetector.Screen.PROFILE -> {
                    // Mon profil : on enlève l'overlay ET on reset le timer
                    hideOverlayOnly()
                    cancelTimerOnly()
                }
                InstagramScreenDetector.Screen.MESSAGES -> {
                    // Messagerie : overlay reste visible MAIS timer reset
                    ensureInstagramOverlayShown()
                    cancelTimerOnly()
                }
                InstagramScreenDetector.Screen.BLOCKED -> {
                    // Feed/Reels/etc : overlay + timer qui tourne
                    ensureInstagramOverlayShown()
                    ensureTimerRunning(currentPackage)
                }
            }
            return
        }

        // --- 3. Autres cibles (TikTok, YouTube) ou danger zone : overlay plein écran ---
        val timerTargetApps = listOf(
            "com.zhiliaoapp.musically",
            "com.google.android.youtube"
        )
        if (timerTargetApps.contains(currentPackage) || isTryingToDisableApp) {
            if (unlockedPackage == currentPackage) {
                hideOverlayOnly()
            } else {
                ensureFullScreenOverlayShown()
                ensureTimerRunning(currentPackage)
            }
        } else {
            // Aucune appli cible au premier plan : reset complet
            unlockedPackage = null
            removeOverlayAndCancelTimer()
        }
    }

    // --- Overlay PLEIN ECRAN (TikTok / YouTube / dangerZone) ---
    private fun ensureFullScreenOverlayShown() {
        if (overlayMode == OverlayMode.TIMER_FULLSCREEN && overlayView != null) return

        // Si un overlay d'un autre type est actif, on le retire avant
        hideOverlayOnly()

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
        windowManager.addView(view, params)
        overlayView = view
        overlayMode = OverlayMode.TIMER_FULLSCREEN
    }

    // --- Overlay PARTIEL Instagram (laisse la bottom nav libre) ---
    private fun ensureInstagramOverlayShown() {
        if (overlayMode == OverlayMode.INSTAGRAM_PARTIAL && overlayView != null) return

        hideOverlayOnly()

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Hauteur réservée en bas de l'écran pour la bottom nav d'Instagram.
        // L'image descend donc plus bas, on ne voit quasiment que la nav d'Insta.
        // Augmenter si la nav est recouverte ; diminuer si trop de contenu
        // Instagram (posts, stories...) reste visible au-dessus de la nav.
        val bottomReserveDp = 120
        val density = resources.displayMetrics.density
        val bottomReservePx = (bottomReserveDp * density).toInt()
        val overlayHeightPx = (resources.displayMetrics.heightPixels - bottomReservePx)
            .coerceAtLeast(200)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayHeightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // FLAG_NOT_TOUCH_MODAL : les touches hors de la fenêtre (= bottom nav)
            //                        passent à Instagram dessous.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        val view = LayoutInflater.from(this).inflate(R.layout.instagram_overlay_layout, null)
        windowManager.addView(view, params)
        overlayView = view
        overlayMode = OverlayMode.INSTAGRAM_PARTIAL
    }

    // --- Timer 100s mutualisé entre tous les overlays ---
    private fun ensureTimerRunning(forPackage: String) {
        if (countDownTimer != null) return  // déjà en cours
        timerPackage = forPackage

        countDownTimer = object : CountDownTimer(100_000, 1_000) {
            override fun onTick(millisUntilFinished: Long) {
                // Met à jour le TextView visible — s'il n'y en a pas (overlay caché
                // pendant un passage sur DM/Profil par ex.), ça no-op silencieusement.
                val secs = "${millisUntilFinished / 1000}s..."
                overlayView?.findViewById<TextView>(R.id.timerTextView)?.text = secs
            }

            override fun onFinish() {
                // Le package qui était en attente peut désormais être utilisé librement
                unlockedPackage = timerPackage
                removeOverlayAndCancelTimer()
            }
        }.start()
    }

    // --- Annule le timer SANS toucher à l'overlay (Messages/Profil Instagram) ---
    private fun cancelTimerOnly() {
        countDownTimer?.cancel()
        countDownTimer = null
        timerPackage = null
        // On remet le texte du timer à sa valeur initiale au cas où l'overlay
        // reste affiché (cas Messages) — comme ça au retour sur Feed on voit bien
        // "100s..." dès que le timer redémarre.
        overlayView?.findViewById<TextView>(R.id.timerTextView)?.text = "100s..."
    }

    // --- Retire l'overlay SANS toucher au timer ---
    private fun hideOverlayOnly() {
        if (overlayView != null) {
            try {
                val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
                windowManager.removeView(overlayView)
            } catch (_: Exception) { /* déjà enlevée */ }
            overlayView = null
        }
        overlayMode = OverlayMode.NONE
    }

    // --- Reset complet : overlay ET timer ---
    private fun removeOverlayAndCancelTimer() {
        countDownTimer?.cancel()
        countDownTimer = null
        timerPackage = null
        hideOverlayOnly()
    }

    override fun onInterrupt() {
        removeOverlayAndCancelTimer()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        val channelId = "focustimer_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Bouclier FocusTimer",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("🛡️ FocusTimer est actif")
                .setContentText("Le bouclier anti-distraction te protège en fond.")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setOngoing(true)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("🛡️ FocusTimer est actif")
                .setContentText("Le bouclier anti-distraction te protège en fond.")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setOngoing(true)
                .build()
        }

        startForeground(1, notification)
    }
}

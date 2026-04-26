package com.example.focustimer

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.CountDownTimer
import android.os.SystemClock
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

    /**
     * Package pour lequel l'utilisateur a attendu les 100s et est donc libre.
     * NB : utilisé UNIQUEMENT pour TikTok/YouTube. Pour Instagram, on n'utilise
     * pas ce mécanisme — chaque changement d'écran ré-évalue la situation.
     */
    private var unlockedPackage: String? = null

    /**
     * Dernier écran Instagram détecté (pour le debug log uniquement).
     */
    private var lastInstagramScreen: InstagramScreenDetector.Screen? = null

    /**
     * Timestamp de la dernière évaluation Insta (throttling pour éviter
     * de spammer sur les WINDOW_CONTENT_CHANGED qui arrivent en rafale).
     */
    private var lastInstagramEvalMs: Long = 0L

    /** Indique que le timer Insta est terminé et qu'on laisse l'user tranquille
     *  tant qu'il reste sur BLOCKED. Si on quitte BLOCKED, ce flag saute. */
    private var instagramFeedFinished: Boolean = false

    /**
     * Doit-on relancer le timer à 100s au prochain écran BLOCKED ?
     * Mis à true dès qu'on quitte la zone BLOCKED (Profil, Messages, autre app).
     * Mis à false quand le timer est effectivement (re)lancé sur BLOCKED.
     * Permet d'éviter que les événements WINDOWS_CHANGED parasites (className=null)
     * ne relancent le timer en boucle alors que l'utilisateur est déjà sur BLOCKED.
     */
    private var instagramNeedsRestart: Boolean = true

    // Distingue l'overlay "plein écran" (TikTok/YouTube)
    // de l'overlay "partiel" (Instagram, bottom nav libre).
    private enum class OverlayMode { NONE, TIMER_FULLSCREEN, INSTAGRAM_PARTIAL }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // On traite :
        //  - WINDOW_STATE_CHANGED / WINDOWS_CHANGED : changement d'écran/activity
        //  - VIEW_SELECTED : changement d'onglet (bottom nav Insta)
        //  - WINDOW_CONTENT_CHANGED : changement de Fragment / contenu (back-up
        //    critique pour Insta car les transitions Feed <-> Messages n'émettent
        //    pas toujours WINDOW_STATE_CHANGED)
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event?.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            event?.eventType != AccessibilityEvent.TYPE_VIEW_SELECTED &&
            event?.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return
        }

        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            FocusLog.d("event type=${eventTypeName(event.eventType)} pkg=${event.packageName} class=${event.className} rootNode=NULL -> skip")
            return
        }
        val currentPackage = rootNode.packageName?.toString() ?: event.packageName?.toString() ?: return

        if (currentPackage == "com.example.focustimer") return

        FocusLog.d(
            "event type=${eventTypeName(event.eventType)} pkg=$currentPackage class=${event.className} " +
                "state{unlockedPkg=$unlockedPackage lastInstaScreen=$lastInstagramScreen feedFinished=$instagramFeedFinished timer=${if (countDownTimer != null) "RUN" else "null"} overlay=$overlayMode}"
        )

        // Si on change de package : on invalide tout état Insta mémorisé
        // (le cooldown ne "transporte" pas entre apps).
        if (unlockedPackage != null && unlockedPackage != currentPackage) {
            unlockedPackage = null
        }
        if (currentPackage != InstagramScreenDetector.PACKAGE) {
            lastInstagramScreen = null
            instagramFeedFinished = false
            instagramNeedsRestart = true   // quitter Insta = reprendre à 0 au retour
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
        //   PROFILE / MESSAGES -> overlay caché + timer reset (zones autorisées)
        //   BLOCKED            -> overlay partiel + timer 100s qui tourne
        //
        // Logique : on ne persiste PAS un "déblocage global" pour Insta. À chaque
        // retour sur une zone bloquée depuis une zone autorisée, le timer repart
        // à 100s. Seul un séjour continu sur BLOCKED permet de laisser finir les
        // 100s et de scroller ensuite.
        if (currentPackage == InstagramScreenDetector.PACKAGE) {
            val isContentChanged =
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

            // Throttle les content_changed : ils arrivent en rafale.
            val now = SystemClock.elapsedRealtime()
            if (isContentChanged && (now - lastInstagramEvalMs) < 250) {
                FocusLog.d("  insta throttled (content_changed < 250ms)")
                return
            }
            lastInstagramEvalMs = now

            val screen = InstagramScreenDetector.detect(event, rootNode)
            FocusLog.d("  insta detected screen=$screen (needsRestart=$instagramNeedsRestart feedFinished=$instagramFeedFinished)")

            when (screen) {
                InstagramScreenDetector.Screen.PROFILE,
                InstagramScreenDetector.Screen.MESSAGES -> {
                    // Zone autorisée : on marque qu'il faudra reprendre à 100s
                    // dès que l'utilisateur reviendra sur une zone bloquée.
                    FocusLog.d("  -> ALLOWED zone, hide overlay + cancel timer + needsRestart=true")
                    instagramNeedsRestart = true
                    instagramFeedFinished = false
                    hideOverlayOnly()
                    cancelTimerOnly()
                }
                InstagramScreenDetector.Screen.BLOCKED -> {
                    if (instagramNeedsRestart) {
                        // Première détection BLOCKED après avoir quitté la zone :
                        // on relance le timer à 100s.
                        FocusLog.d("  -> BLOCKED (fresh entry), start fresh 100s timer")
                        instagramNeedsRestart = false
                        instagramFeedFinished = false
                        cancelTimerOnly()
                        ensureInstagramOverlayShown()
                        ensureTimerRunning(currentPackage)
                    } else {
                        // Déjà en zone BLOCKED : on continue sans toucher au timer.
                        if (instagramFeedFinished) {
                            FocusLog.d("  -> BLOCKED (stay) + feedFinished=true, hide overlay")
                            hideOverlayOnly()
                        } else {
                            FocusLog.d("  -> BLOCKED (stay) + feedFinished=false, ensure overlay+timer")
                            ensureInstagramOverlayShown()
                            ensureTimerRunning(currentPackage)
                        }
                    }
                }
            }

            lastInstagramScreen = screen
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
                // Si un timer pour un autre package tourne encore, on l'annule
                // avant de démarrer celui de ce package.
                if (timerPackage != null && timerPackage != currentPackage) {
                    cancelTimerOnly()
                }
                ensureFullScreenOverlayShown()
                ensureTimerRunning(currentPackage)
            }
        } else {
            // Aucune appli cible au premier plan : reset complet
            unlockedPackage = null
            removeOverlayAndCancelTimer()
        }
    }

    private fun eventTypeName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "WINDOWS_CHANGED"
        AccessibilityEvent.TYPE_VIEW_SELECTED -> "VIEW_SELECTED"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
        else -> "TYPE_$type"
    }

    // --- Overlay PLEIN ECRAN (TikTok / YouTube / dangerZone) ---
    private fun ensureFullScreenOverlayShown() {
        if (overlayMode == OverlayMode.TIMER_FULLSCREEN && overlayView != null) return

        hideOverlayOnly()

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Couvre tout l'écran sauf la barre de navigation système (home/retour).
        val navBarHeight = getNavigationBarHeight()
        val overlayHeightPx = (resources.displayMetrics.heightPixels - navBarHeight)
            .coerceAtLeast(200)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayHeightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

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
                val finishedFor = timerPackage
                FocusLog.d("timer FINISHED for pkg=$finishedFor")
                if (finishedFor == InstagramScreenDetector.PACKAGE) {
                    instagramFeedFinished = true
                } else {
                    unlockedPackage = finishedFor
                }
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

    private fun getNavigationBarHeight(): Int {
        val resId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    }

    override fun onInterrupt() {
        removeOverlayAndCancelTimer()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        FocusLog.init(this, truncate = true)
        FocusLog.d("onServiceConnected")

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

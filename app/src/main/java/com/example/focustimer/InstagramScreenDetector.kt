package com.example.focustimer

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Détecteur à trois états pour Instagram.
 *
 *   PROFILE  -> onglet Profil (mien). Overlay caché + timer reset.
 *   MESSAGES -> DMs / Inbox / conversation. Overlay caché + timer reset.
 *   BLOCKED  -> Feed / Reels / Explore / etc. Overlay + timer 100s qui tourne.
 *
 * PROFILE et MESSAGES sont tous deux "autorisés" — le service les traite
 * de manière identique (pas d'overlay, timer stoppé/reset).
 *
 * Trois couches de détection, de la plus stable à la plus fragile :
 *   1) Nom de classe de l'Activity/Fragment (event.className)
 *   2) TEXTES VISIBLES dans la page (très fiable car localisés = stables)
 *   3) Onglet sélectionné dans la bottom nav
 *
 * Fail-safe restrictif : si rien ne matche, on considère BLOCKED.
 */
object InstagramScreenDetector {

    const val PACKAGE = "com.instagram.android"

    enum class Screen { PROFILE, MESSAGES, BLOCKED }

    // ============================================================
    // Signaux CLASS NAME (event.className)
    // ============================================================
    private val PROFILE_CLASS_KEYWORDS = listOf(
        "userdetail",
        "profilemedia",
        "ownprofile"
    )
    private val MESSAGES_CLASS_KEYWORDS = listOf(
        "direct",
        "inbox",
        "thread",
        "conversation",
        "chat"
    )

    // ============================================================
    // Signaux TEXTES VISIBLES (findAccessibilityNodeInfosByText)
    // ============================================================
    // Ces chaînes font un match insensible à la casse + substring.
    // Elles ne doivent apparaître QUE sur l'écran concerné.
    // Ajuste les chaînes ici si Instagram met à jour sa traduction.

    private val MESSAGES_VISIBLE_TEXTS = listOf(
        // --- INBOX (liste des conversations) ---
        // Barre de recherche Meta AI en haut de l'inbox : uniquement visible dans l'inbox.
        "Recherchez ou demandez à Meta AI",
        "Search or ask Meta AI",
        "Demandez à Meta AI",
        "Ask Meta AI",
        // NOTE : "Nouveau message" / "New message" intentionnellement ABSENT :
        // c'est le label d'accessibilité du bouton DM dans la bottom nav,
        // visible sur TOUS les écrans d'Instagram. Le garder ici provoque de
        // faux positifs MESSAGES sur le Feed/Reels.
        // NOTE : "Envoyer un message" aussi absent : label du bouton DM sur les posts.
        // NOTE : "Message…" / "Message..." aussi absent : trop générique.

        // --- CONVERSATION (thread individuel) ---
        // Ces textes n'apparaissent QUE dans l'en-tête d'une conversation DM.
        "Appel vidéo",
        "Video call",
        "Appel vocal",
        "Voice call",
        "Actif maintenant",
        "Active now",
        "Actif il y a"
    )

    private val PROFILE_VISIBLE_TEXTS = listOf(
        "Modifier le profil",
        "Edit profile",
        "Partager le profil",
        "Share profile",
        "Professional dashboard",
        "Tableau de bord professionnel"
    )

    // ============================================================
    // Signaux ONGLET SELECTIONNE dans la bottom nav
    // ============================================================
    private val PROFILE_TAB_KEYWORDS = listOf(
        "profile",
        "profil",
        "your profile",
        "votre profil",
        "mon profil"
    )

    // "envoyer un message" / "send message" = label du bouton DM dans la bottom nav
    // quand cet onglet est sélectionné (visible dans les logs comme selectedDesc).
    private val MESSAGES_TAB_KEYWORDS = listOf(
        "envoyer un message",
        "send message",
        "direct",
        "messages",
        "inbox"
    )

    fun detect(event: AccessibilityEvent?, rootNode: AccessibilityNodeInfo?): Screen {
        // --- 1) Par class name (très rapide) ---
        val className = event?.className?.toString()?.lowercase().orEmpty()
        if (className.isNotEmpty()) {
            val matchedProfile = PROFILE_CLASS_KEYWORDS.firstOrNull { className.contains(it) }
            if (matchedProfile != null) {
                FocusLog.d("    detector[1-className]: PROFILE (kw='$matchedProfile' in '$className')")
                return Screen.PROFILE
            }
            val matchedMsg = MESSAGES_CLASS_KEYWORDS.firstOrNull { className.contains(it) }
            if (matchedMsg != null) {
                FocusLog.d("    detector[1-className]: MESSAGES (kw='$matchedMsg' in '$className')")
                return Screen.MESSAGES
            }
        }

        // --- 2) Par texte visible (le plus fiable aux updates Insta) ---
        //
        // CRITIQUE : on filtre par isVisibleToUser. Instagram garde les fragments
        // de la bottom nav (Home/Reels/Profile/...) vivants en mémoire même
        // quand ils ne sont pas affichés. Leurs nodes texte restent dans l'arbre
        // d'accessibilité ; sans filtre on matcherait "Modifier le profil" même
        // en étant sur le Feed.
        if (rootNode != null) {
            try {
                for (text in MESSAGES_VISIBLE_TEXTS) {
                    val (total, visible) = countMatches(rootNode, text)
                    if (visible > 0) {
                        FocusLog.d("    detector[2-text]: MESSAGES (text='$text' visible=$visible/$total)")
                        return Screen.MESSAGES
                    } else if (total > 0) {
                        FocusLog.d("    detector[2-text]: skip '$text' (total=$total all hidden)")
                    }
                }
                for (text in PROFILE_VISIBLE_TEXTS) {
                    val (total, visible) = countMatches(rootNode, text)
                    if (visible > 0) {
                        FocusLog.d("    detector[2-text]: PROFILE (text='$text' visible=$visible/$total)")
                        return Screen.PROFILE
                    } else if (total > 0) {
                        FocusLog.d("    detector[2-text]: skip '$text' (total=$total all hidden)")
                    }
                }
            } catch (e: Exception) {
                FocusLog.w("    detector[2-text]: exception ${e.message}")
            }
        }

        // --- 3) Par onglet sélectionné dans la bottom nav ---
        val selectedDesc = findSelectedContentDescription(rootNode)?.lowercase().orEmpty()
        if (selectedDesc.isNotEmpty()) {
            val matchedProfile = PROFILE_TAB_KEYWORDS.firstOrNull { selectedDesc.contains(it) }
            if (matchedProfile != null) {
                FocusLog.d("    detector[3-tab]: PROFILE (kw='$matchedProfile' in '$selectedDesc')")
                return Screen.PROFILE
            }
            val matchedMsg = MESSAGES_TAB_KEYWORDS.firstOrNull { selectedDesc.contains(it) }
            if (matchedMsg != null) {
                FocusLog.d("    detector[3-tab]: MESSAGES (kw='$matchedMsg' in '$selectedDesc')")
                return Screen.MESSAGES
            }
            FocusLog.d("    detector[3-tab]: no match (selectedDesc='$selectedDesc')")
        } else {
            FocusLog.d("    detector[3-tab]: no selected node found")
        }

        // --- Fail-safe restrictif ---
        FocusLog.d("    detector: no layer matched -> FAIL-SAFE BLOCKED (className='$className')")
        return Screen.BLOCKED
    }

    /**
     * Retourne (totalMatches, visibleMatches). On ne considère qu'un match visible
     * à l'écran : sinon on risque de matcher des fragments pré-chargés par Insta
     * (ViewPager qui garde Home/Reels/Profile vivants même cachés).
     */
    private fun countMatches(root: AccessibilityNodeInfo, text: String): Pair<Int, Int> {
        val matches = root.findAccessibilityNodeInfosByText(text)
        if (matches.isNullOrEmpty()) return 0 to 0
        var visible = 0
        for (node in matches) {
            try {
                if (node.isVisibleToUser) visible++
            } catch (_: Exception) { /* node stale, on passe */ }
        }
        return matches.size to visible
    }

    private fun findSelectedContentDescription(root: AccessibilityNodeInfo?): String? {
        if (root == null) return null
        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.add(root)
        var visited = 0
        val maxVisited = 3000

        while (queue.isNotEmpty() && visited < maxVisited) {
            val node = queue.removeFirst()
            visited++

            if (node.isSelected) {
                val desc = node.contentDescription?.toString()
                if (!desc.isNullOrBlank()) return desc
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }
}

package com.example.focustimer

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Détecteur à trois états pour Instagram.
 *
 *   PROFILE  -> onglet Profil (mien). Overlay caché + timer reset.
 *   MESSAGES -> DMs / Inbox / conversation. Overlay GARDE mais timer reset
 *               (on ne peut donc pas attendre en messagerie pour unlock Reels).
 *   BLOCKED  -> Feed / Reels / Explore / etc. Overlay + timer 100s qui tourne.
 *
 * Signaux utilisés, du plus stable au plus fragile :
 *   1) Nom de classe de l'Activity/Fragment (event.className)
 *   2) Onglet sélectionné dans la bottom nav (node.isSelected + contentDescription)
 *
 * Fail-safe restrictif : si rien ne matche, on considère BLOCKED.
 */
object InstagramScreenDetector {

    const val PACKAGE = "com.instagram.android"

    enum class Screen { PROFILE, MESSAGES, BLOCKED }

    // --- Classes qui correspondent à la page Profil (mienne) ---
    private val PROFILE_CLASS_KEYWORDS = listOf(
        "userdetail",
        "profilemedia",
        "ownprofile"
    )

    // --- Classes qui correspondent à la messagerie (DM) ---
    private val MESSAGES_CLASS_KEYWORDS = listOf(
        "direct",
        "inbox",
        "thread"
    )

    // --- Onglet Profile sélectionné dans la bottom nav ---
    private val PROFILE_TAB_KEYWORDS = listOf(
        "profile",
        "profil",
        "your profile",
        "votre profil",
        "mon profil"
    )

    fun detect(event: AccessibilityEvent?, rootNode: AccessibilityNodeInfo?): Screen {
        val className = event?.className?.toString()?.lowercase().orEmpty()
        if (className.isNotEmpty()) {
            if (PROFILE_CLASS_KEYWORDS.any { className.contains(it) }) return Screen.PROFILE
            if (MESSAGES_CLASS_KEYWORDS.any { className.contains(it) }) return Screen.MESSAGES
        }

        val selectedDesc = findSelectedContentDescription(rootNode)?.lowercase().orEmpty()
        if (selectedDesc.isNotEmpty()) {
            if (PROFILE_TAB_KEYWORDS.any { selectedDesc.contains(it) }) return Screen.PROFILE
            // Les DMs ne sont généralement pas dans la bottom nav (icône en haut
            // à droite d'Insta), donc pas de détection par tab sélectionnée ici.
        }

        return Screen.BLOCKED
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

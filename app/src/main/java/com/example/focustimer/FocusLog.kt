package com.example.focustimer

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logger de debug qui écrit à la fois dans logcat (adb logcat -s FocusTimer:D)
 * et dans un fichier persistant sur le téléphone.
 *
 * Fichier : /storage/emulated/0/Android/data/com.example.focustimer/files/focustimer.log
 *
 * Récupérer le fichier sur le PC :
 *   adb pull /storage/emulated/0/Android/data/com.example.focustimer/files/focustimer.log C:\DEV\AndroidStudio\focustimer.log
 *
 * Le fichier est tronqué (vidé) à chaque démarrage du service pour éviter
 * qu'il ne grossisse indéfiniment. Taille max douce : 2 Mo (rotation simple).
 */
object FocusLog {

    private const val TAG = "FocusTimer"
    private const val FILE_NAME = "focustimer.log"
    private const val MAX_FILE_SIZE_BYTES = 2L * 1024 * 1024  // 2 Mo

    private val TS_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.FRANCE)

    @Volatile private var logFile: File? = null
    private val lock = Any()

    /** À appeler une fois au démarrage du service (onServiceConnected). */
    fun init(context: Context, truncate: Boolean = true) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val f = File(dir, FILE_NAME)
        try {
            if (truncate && f.exists()) f.delete()
            f.createNewFile()
            synchronized(lock) { logFile = f }
            d("=== FocusLog started === path=${f.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "FocusLog init failed", e)
        }
    }

    fun d(msg: String) {
        Log.d(TAG, msg)
        writeToFile("D", msg)
    }

    fun w(msg: String, tr: Throwable? = null) {
        if (tr != null) Log.w(TAG, msg, tr) else Log.w(TAG, msg)
        writeToFile("W", msg + (tr?.let { " :: ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
    }

    fun e(msg: String, tr: Throwable? = null) {
        if (tr != null) Log.e(TAG, msg, tr) else Log.e(TAG, msg)
        writeToFile("E", msg + (tr?.let { " :: ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
    }

    private fun writeToFile(level: String, msg: String) {
        val f = synchronized(lock) { logFile } ?: return
        try {
            // Rotation simple : si le fichier dépasse la taille max, on le vide.
            if (f.length() > MAX_FILE_SIZE_BYTES) {
                try { f.writeText("[rotated @ ${TS_FORMAT.format(Date())}]\n") } catch (_: Exception) {}
            }
            PrintWriter(FileWriter(f, /* append = */ true)).use { pw ->
                pw.println("${TS_FORMAT.format(Date())} $level  $msg")
            }
        } catch (e: Exception) {
            // On ne relog pas pour éviter une boucle infinie.
            Log.w(TAG, "FocusLog write failed: ${e.message}")
        }
    }
}

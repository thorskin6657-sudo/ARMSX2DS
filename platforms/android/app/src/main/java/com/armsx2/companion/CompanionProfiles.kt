package com.armsx2.companion

import android.content.Context
import java.io.File

/**
 * Finds the companion profile for the running game.
 *
 * Two places are read, and the second wins: profiles bundled in the app (assets/companion/profiles)
 * and profiles the user drops into `<app files>/companion/profiles/` (Android/data/<package>/files).
 * The user folder exists so a profile can be fixed or added without rebuilding the app.
 */
object CompanionProfiles {
    private const val ASSET_DIR = "companion/profiles"
    private var bySerial: Map<String, CompanionProfile> = emptyMap()
    private var loaded = false

    fun userDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "companion/profiles")

    /** Re-read everything. Cheap (a handful of small JSON files); call after adding a profile. */
    @Synchronized
    fun reload(context: Context) {
        val found = LinkedHashMap<String, CompanionProfile>()

        runCatching {
            context.assets.list(ASSET_DIR)?.filter { it.endsWith(".json") }?.forEach { name ->
                val text = context.assets.open("$ASSET_DIR/$name").bufferedReader().use { it.readText() }
                CompanionProfileParser.parse(text)?.let { p -> p.serials.forEach { found[it] = p } }
            }
        }

        runCatching {
            userDir(context).listFiles { f -> f.extension == "json" }?.sortedBy { it.name }?.forEach { f ->
                CompanionProfileParser.parse(f.readText())?.let { p -> p.serials.forEach { found[it] = p } }
            }
        }

        bySerial = found
        loaded = true
    }

    /** The profile for a disc serial like "SLUS-20000", or null when the game has none. */
    @Synchronized
    fun forSerial(context: Context, serial: String?): CompanionProfile? {
        if (serial.isNullOrBlank()) return null
        if (!loaded) reload(context)
        return bySerial[serial.trim().uppercase()]
    }
}

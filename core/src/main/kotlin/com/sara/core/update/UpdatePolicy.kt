package com.sara.core.update

/**
 * Las versiones publicadas se llaman SaraAssistantV<n>, donde n es el número de compilación de
 * GitHub Actions y también el versionCode del APK.
 */
object UpdatePolicy {
    private val TAG_NUMBER = Regex("""(?i)^saraassistantv(\d+)$""")

    fun versionFromTag(tag: String): Int? = TAG_NUMBER.find(tag.trim())?.groupValues?.get(1)?.toIntOrNull()

    /** Hay actualización sólo si el release es realmente más nuevo que lo instalado. */
    fun isNewer(tag: String, installedVersionCode: Int): Boolean {
        val remote = versionFromTag(tag) ?: return false
        return remote > installedVersionCode
    }
}

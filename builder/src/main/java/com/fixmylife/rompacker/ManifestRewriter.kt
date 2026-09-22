package com.fixmylife.rompacker

import java.text.Normalizer

/**
 * The player template is built with a throwaway applicationId and a literal
 * placeholder label. This swaps both for the game's values.
 *
 * Anything *prefixed* with the template id is renamed too, because AndroidX adds
 * entries like "<appId>.androidx-startup" (provider authority) and
 * "<appId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION". If those stayed identical
 * across games, the second game would fail to install with a duplicate
 * permission / provider conflict.
 *
 * Class names are safe: the player's code lives in a different namespace
 * (com.fixmylife.romplayer), so they never start with the template id.
 */
object ManifestRewriter {
    const val TEMPLATE_PACKAGE = "fixmylife.rompack.template"
    const val LABEL_PLACEHOLDER = "ROMPACK_LABEL_PLACEHOLDER"

    fun rewrite(manifest: ByteArray, newPackage: String, label: String): ByteArray {
        val patched = AxmlPatcher.patch(manifest) { s ->
            when {
                s == LABEL_PLACEHOLDER -> label
                s == TEMPLATE_PACKAGE -> newPackage
                s.startsWith("$TEMPLATE_PACKAGE.") -> newPackage + s.removePrefix(TEMPLATE_PACKAGE)
                else -> s
            }
        }
        val strings = AxmlPatcher.readStrings(patched)
        check(newPackage in strings) { "Template manifest did not contain $TEMPLATE_PACKAGE" }
        check(label in strings) { "Template manifest did not contain the label placeholder" }
        return patched
    }

    private val PACKAGE_RE = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

    fun isValidPackage(name: String) = PACKAGE_RE.matches(name) && name.length <= 200

    fun suggestPackage(title: String): String {
        val ascii = Normalizer.normalize(title, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        var slug = ascii.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(30)
        if (slug.isEmpty()) slug = "game"
        if (slug.first().isDigit()) slug = "g$slug"
        return "com.fixmylife.rom.$slug"
    }
}

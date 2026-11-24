package com.anatej.c2s

/** Conservative content classification for selected OCR text. */
object SmartContent {

    enum class Type { URL, EMAIL, PHONE, MAP }
    data class Action(val type: Type, val value: String)

    private val urlRegex = Regex("(?i)\\b((?:https?://|www\\.)[^\\s<>()]+)")
    private val emailRegex = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
    private val phoneRegex = Regex("^[+]?[0-9][0-9\\s().-]{6,24}$")
    private val addressRegex = Regex(
        "(?i)(street|str\\.|road|rd\\.|avenue|ave\\.|lane|ln\\.|drive|dr\\.|nagar|marg|house|flat|block|sector)"
    )

    fun detect(text: String): List<Action> {
        val value = text.trim()
        if (value.isEmpty()) return emptyList()
        val actions = ArrayList<Action>()

        urlRegex.findAll(value).forEach { match ->
            val link = match.value.trimEnd('.', ',', ';', ':', '!', '?')
            val normalized = if (link.startsWith("http", ignoreCase = true)) link else "https://$link"
            actions += Action(Type.URL, normalized)
        }
        emailRegex.findAll(value).forEach { match ->
            actions += Action(Type.EMAIL, match.value)
        }

        val digits = value.filter { it.isDigit() }
        if (phoneRegex.matches(value) && digits.length in 7..15) {
            actions += Action(Type.PHONE, value.filter { it.isDigit() || it == '+' })
        }
        if (value.length in 8..160 && value.any { it.isDigit() } && addressRegex.containsMatchIn(value)) {
            actions += Action(Type.MAP, value)
        }

        return actions.distinct().take(4)
    }
}

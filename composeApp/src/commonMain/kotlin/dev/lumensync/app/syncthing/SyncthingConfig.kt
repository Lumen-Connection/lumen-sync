package dev.lumensync.app.syncthing

private val guiAddressPattern = Regex(
    pattern = """<gui\b[^>]*>.*?<address>\s*([^<]+?)\s*</address>""",
    options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

internal fun syncthingGuiBaseUrl(configXml: String): String? {
    val address = guiAddressPattern.find(configXml)?.groupValues?.get(1)?.trim() ?: return null
    val hostAndPort = when {
        address.startsWith("http://", ignoreCase = true) -> address.substringAfter("://")
        "://" in address -> return null
        else -> address
    }

    val (host, portText) = if (hostAndPort.startsWith("[")) {
        val closingBracket = hostAndPort.indexOf(']')
        if (closingBracket < 0 || hostAndPort.getOrNull(closingBracket + 1) != ':') return null
        hostAndPort.substring(1, closingBracket) to hostAndPort.substring(closingBracket + 2)
    } else {
        val separator = hostAndPort.lastIndexOf(':')
        if (separator <= 0 || ':' in hostAndPort.substring(0, separator)) return null
        hostAndPort.substring(0, separator) to hostAndPort.substring(separator + 1)
    }

    val normalizedHost = host.lowercase()
    if (normalizedHost !in setOf("127.0.0.1", "localhost", "::1")) return null
    val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    val formattedHost = if (normalizedHost == "::1") "[::1]" else normalizedHost
    return "http://$formattedHost:$port"
}

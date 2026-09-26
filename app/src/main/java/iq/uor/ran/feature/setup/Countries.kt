package iq.uor.ran.feature.setup

import iq.uor.ran.core.data.*

/** A country and how its phone numbers look. */
data class Country(val iso: String, val name: String, val flag: String, val dial: String, val minLen: Int, val maxLen: Int)

/**
 * Phone numbers are the identity in Ran, so they must be entered correctly.
 * The user picks a country (Iraq by default) and types the number without the leading zero.
 */
object Countries {
    val IRAQ = Country("IQ", "Iraq", "🇮🇶", "964", 10, 10)

    val all: List<Country> = listOf(
        IRAQ,
        Country("TR", "Türkiye", "🇹🇷", "90", 10, 10),
        Country("IR", "Iran", "🇮🇷", "98", 10, 10),
        Country("SY", "Syria", "🇸🇾", "963", 9, 9),
        Country("JO", "Jordan", "🇯🇴", "962", 9, 9),
        Country("LB", "Lebanon", "🇱🇧", "961", 7, 8),
        Country("SA", "Saudi Arabia", "🇸🇦", "966", 9, 9),
        Country("AE", "United Arab Emirates", "🇦🇪", "971", 9, 9),
        Country("QA", "Qatar", "🇶🇦", "974", 8, 8),
        Country("KW", "Kuwait", "🇰🇼", "965", 8, 8),
        Country("BH", "Bahrain", "🇧🇭", "973", 8, 8),
        Country("OM", "Oman", "🇴🇲", "968", 8, 8),
        Country("EG", "Egypt", "🇪🇬", "20", 10, 10),
        Country("DE", "Germany", "🇩🇪", "49", 10, 11),
        Country("GB", "United Kingdom", "🇬🇧", "44", 10, 10),
        Country("NL", "Netherlands", "🇳🇱", "31", 9, 9),
        Country("SE", "Sweden", "🇸🇪", "46", 7, 9),
        Country("NO", "Norway", "🇳🇴", "47", 8, 8),
        Country("DK", "Denmark", "🇩🇰", "45", 8, 8),
        Country("FI", "Finland", "🇫🇮", "358", 9, 10),
        Country("FR", "France", "🇫🇷", "33", 9, 9),
        Country("IT", "Italy", "🇮🇹", "39", 9, 10),
        Country("ES", "Spain", "🇪🇸", "34", 9, 9),
        Country("BE", "Belgium", "🇧🇪", "32", 9, 9),
        Country("AT", "Austria", "🇦🇹", "43", 10, 11),
        Country("CH", "Switzerland", "🇨🇭", "41", 9, 9),
        Country("GR", "Greece", "🇬🇷", "30", 10, 10),
        Country("US", "United States", "🇺🇸", "1", 10, 10),
        Country("CA", "Canada", "🇨🇦", "1", 10, 10),
        Country("AU", "Australia", "🇦🇺", "61", 9, 9),
        Country("IN", "India", "🇮🇳", "91", 10, 10),
        Country("PK", "Pakistan", "🇵🇰", "92", 10, 10),
        Country("AF", "Afghanistan", "🇦🇫", "93", 9, 9),
        Country("MY", "Malaysia", "🇲🇾", "60", 9, 10),
        Country("RU", "Russia", "🇷🇺", "7", 10, 10),
        Country("UA", "Ukraine", "🇺🇦", "380", 9, 9),
        Country("CN", "China", "🇨🇳", "86", 11, 11),
        Country("JP", "Japan", "🇯🇵", "81", 10, 10),
        Country("KR", "South Korea", "🇰🇷", "82", 9, 10)
    )

    fun byIso(iso: String): Country = all.firstOrNull { it.iso == iso } ?: IRAQ

    /** Longest matching dial code wins (+1 vs +964). */
    fun byNumber(e164: String): Country? {
        val d = e164.removePrefix("+").filter(Char::isDigit)
        return all.filter { d.startsWith(it.dial) }.maxByOrNull { it.dial.length }
    }

    /** National part typed by the user, cleaned: digits only, no leading zero. */
    fun national(raw: String): String = raw.filter(Char::isDigit).trimStart('0')

    /** Full international number, e.g. Iraq + "750 123 4567" → "+9647501234567". */
    fun e164(c: Country, raw: String): String = "+" + c.dial + national(raw)

    /** null = the number looks right; otherwise a translation key explaining what is wrong. */
    fun problem(c: Country, raw: String): String? {
        val n = national(raw)
        return when {
            n.isEmpty() -> "err_phone_empty"
            n.length < c.minLen -> "err_phone_short"
            n.length > c.maxLen -> "err_phone_long"
            c.iso == "IQ" && !n.startsWith("7") -> "err_phone_iq"
            else -> null
        }
    }

    fun isValid(c: Country, raw: String) = problem(c, raw) == null

    /** "+9647501234567" → "+964 750 123 4567" */
    fun pretty(e164: String): String {
        if (!e164.startsWith("+")) return e164
        val c = byNumber(e164) ?: return e164
        val n = e164.removePrefix("+").removePrefix(c.dial)
        val groups = when {
            n.length >= 10 -> listOf(n.substring(0, 3), n.substring(3, 6), n.substring(6))
            n.length >= 7 -> listOf(n.substring(0, 3), n.substring(3))
            else -> listOf(n)
        }
        return "+" + c.dial + " " + groups.joinToString(" ")
    }

    /** What the user sees for a contact: their saved name, or their number. */
    fun display(e164: String) = pretty(e164)
}

package iq.uor.ran.core.data

/** Who made Ran and how to reach him. Shown on the About screen and in the stores. */
object AppInfo {
    const val APP_NAME = "Ran"
    const val VERSION = "1.0"
    const val DEVELOPER = "Nawzad Rasul Mohammed"
    const val EMAIL = "nawzadrasul92@gmail.com"
    const val ADDRESS = "Ranya, Sulaymaniyah – Kurdistan Region, Iraq"
    /** International form, e.g. "+9647XXXXXXXXX". Leave empty to hide the WhatsApp button. */
    const val WHATSAPP = ""
    const val ORGANISATION = "University of Raparin – English Department"
    val whatsappLink: String get() = "https://wa.me/" + WHATSAPP.filter { it.isDigit() }
}

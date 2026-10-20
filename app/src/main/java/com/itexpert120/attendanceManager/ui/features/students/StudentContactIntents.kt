package com.itexpert120.attendanceManager.ui.features.students

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/** The ways to reach a student's parent from the app. */
enum class ContactAction { CALL, SMS, WHATSAPP }

private const val WHATSAPP = "com.whatsapp"
private const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"

/**
 * Opens the dialer, the messaging app or a WhatsApp chat for [phone]. None of these need a permission:
 * the dialer is only pre-filled (`ACTION_DIAL`), and SMS / WhatsApp only open a compose screen. Returns false
 * when nothing on the device can handle it. (The activity is started directly and the exception caught,
 * instead of querying the package manager first, so no `<queries>` entry is needed on Android 11+.)
 */
fun launchContact(context: Context, action: ContactAction, phone: String): Boolean {
    if (phoneDigits(phone).isEmpty()) return false
    return when (action) {
        ContactAction.CALL -> tryStart(context, Intent(Intent.ACTION_DIAL, telUriText(phone).toUri()))
        ContactAction.SMS -> tryStart(context, Intent(Intent.ACTION_SENDTO, smsUriText(phone).toUri()))
        ContactAction.WHATSAPP -> {
            val chat = whatsAppUriText(phone).toUri()
            // WhatsApp itself, then WhatsApp Business, then whatever handles the wa.me link (usually the browser).
            tryStart(context, Intent(Intent.ACTION_VIEW, chat).setPackage(WHATSAPP)) ||
                tryStart(context, Intent(Intent.ACTION_VIEW, chat).setPackage(WHATSAPP_BUSINESS)) ||
                tryStart(context, Intent(Intent.ACTION_VIEW, chat))
        }
    }
}

private fun tryStart(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

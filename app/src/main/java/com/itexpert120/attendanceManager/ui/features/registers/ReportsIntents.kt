package com.itexpert120.attendanceManager.ui.features.registers

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * Opens the parent's SMS, WhatsApp or dialler apps with the message prefilled. Nothing is sent
 * automatically: the teacher always confirms in the target app. Each function returns whether an app
 * handled the intent, so the UI can say so instead of silently doing nothing.
 */
internal object ParentContact {
    private const val WHATSAPP_PACKAGE = "com.whatsapp"

    fun sms(context: Context, phone: String, message: String): Boolean {
        val intent = Intent(Intent.ACTION_SENDTO, smsUriString(phone).toUri()).putExtra("sms_body", message)
        return start(context, intent)
    }

    /** Tries the WhatsApp app first and falls back to any app that opens wa.me links (Business, browser). */
    fun whatsapp(context: Context, phone: String, message: String): Boolean {
        val uri = whatsappUrl(phone, message).toUri()
        val direct = Intent(Intent.ACTION_VIEW, uri).setPackage(WHATSAPP_PACKAGE)
        return start(context, direct) || start(context, Intent(Intent.ACTION_VIEW, uri))
    }

    /** ACTION_DIAL only opens the dialler with the number filled in; it needs no call permission. */
    fun dial(context: Context, phone: String): Boolean =
        start(context, Intent(Intent.ACTION_DIAL, dialUriString(phone).toUri()))

    private fun start(context: Context, intent: Intent): Boolean {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}

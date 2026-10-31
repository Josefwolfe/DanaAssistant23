package ir.dana.assistant

import android.app.Notification
import android.app.RemoteInput
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class DanaNotificationListener : NotificationListenerService() {

    data class CapturedMessage(
        val packageName: String,
        val appName: String,
        val sender: String,
        val text: String,
        val timestamp: Long,
        val replyAction: Notification.Action?
    )

    companion object {
        private const val TAG = "DanaNotifListener"
        var instance: DanaNotificationListener? = null
            private set

        val recentMessages = mutableListOf<CapturedMessage>()
        var lastExtractedOtp: String? = null

        fun getLatestMessagesSpeech(maxCount: Int = 3): String {
            if (recentMessages.isEmpty()) {
                return "هیچ پیام یا اعلان جدیدی دریافت نشده است."
            }
            val latest = recentMessages.takeLast(maxCount).reversed()
            return buildString {
                append("آخرین پیام‌های شما: ")
                latest.forEachIndexed { idx, msg ->
                    append("پیام ${idx + 1} در ${msg.appName} از طرف ${msg.sender}: ${msg.text}. ")
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "سرویس اعلان‌های دانا متصل شد.")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.isOngoing) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        val appName = when {
            pkg.contains("eitaa") -> "ایتا"
            pkg.contains("whatsapp") -> "واتساپ"
            pkg.contains("telegram") -> "تلگرام"
            pkg.contains("rubika") || pkg.contains("resaneh1") -> "روبیکا"
            pkg.contains("nasim") -> "بله"
            pkg.contains("soroush") || pkg.contains("mmdt") -> "سروش"
            pkg.contains("mms") || pkg.contains("messaging") || pkg.contains("messages") -> "پیامک"
            else -> "برنامه"
        }

        val replyAction = sbn.notification.actions?.firstOrNull { action ->
            action.remoteInputs?.isNotEmpty() == true
        }

        if (recentMessages.size >= 25) {
            recentMessages.removeAt(0)
        }
        recentMessages.add(
            CapturedMessage(
                packageName = pkg,
                appName = appName,
                sender = title,
                text = text,
                timestamp = System.currentTimeMillis(),
                replyAction = replyAction
            )
        )

        val prefs = getSharedPreferences("dana_prefs", Context.MODE_PRIVATE)
        val autoOtpEnabled = prefs.getBoolean("auto_otp_enabled", true)
        val drivingModeEnabled = prefs.getBoolean("driving_mode_enabled", false)

        if (autoOtpEnabled) {
            val otpCode = extractBankOtpOrVerificationCode("$title $text")
            if (otpCode != null) {
                lastExtractedOtp = otpCode
                copyToClipboard(otpCode)
                val typed = DanaAccessibilityService.instance?.inputText(
                    textToType = otpCode,
                    targetFieldQuery = "رمز"
                ) ?: false

                val msg = if (typed) {
                    "رمز پویا $otpCode دریافت شد و به صورت خودکار در کادر رمز وارد شد."
                } else {
                    "رمز پویا $otpCode دریافت و در حافظه کپی شد."
                }
                PersianVoiceSpeaker.sharedInstance?.speakPersian(msg)
                return
            }
        }

        if (drivingModeEnabled && appName != "برنامه") {
            val announce = "پیام جدید در $appName از طرف $title: $text"
            PersianVoiceSpeaker.sharedInstance?.speakPersian(announce)
        }
    }

    private fun extractBankOtpOrVerificationCode(rawMessage: String): String? {
        val normalized = convertPersianDigits(rawMessage)
        val keywords = listOf("رمز پویا", "رمز دوم", "بانک", "کد تایید", "کد ورود", "پویا", "otp", "code")
        if (keywords.none { normalized.contains(it, ignoreCase = true) }) return null

        val regex = Regex("(?<!\\d)(\\d{5,8})(?!\\d)")
        val matches = regex.findAll(normalized).map { it.groupValues[1] }.toList()
        return matches.firstOrNull()
    }

    private fun convertPersianDigits(input: String): String {
        val persianDigits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
        val arabicDigits = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')
        var out = input
        for (i in 0..9) {
            out = out.replace(persianDigits[i], ('0' + i)).replace(arabicDigits[i], ('0' + i))
        }
        return out
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Dana_OTP", text))
        } catch (e: Exception) {
            Log.e(TAG, "خطا در کپی رمز پویا", e)
        }
    }

    fun replyToSenderIfAvailable(senderQuery: String, replyMessage: String): Boolean {
        val target = recentMessages.lastOrNull {
            it.sender.contains(senderQuery, ignoreCase = true) && it.replyAction != null
        } ?: return false

        val action = target.replyAction ?: return false
        val remoteInputs = action.remoteInputs ?: return false

        return try {
            val fillInIntent = Intent()
            val bundle = Bundle()
            for (remoteInput in remoteInputs) {
                bundle.putCharSequence(remoteInput.resultKey, replyMessage)
            }
            RemoteInput.addResultsToIntent(remoteInputs, fillInIntent, bundle)
            action.actionIntent.send(this, 0, fillInIntent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "خطا در پاسخ مستقیم به اعلان", e)
            false
        }
    }
}

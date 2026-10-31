package ir.dana.assistant

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class DanaAiAgent(
    private val context: Context,
    private val systemExecutor: SystemActionExecutor
) {

    companion object {
        private const val TAG = "DanaAiAgent"
        private const val MAX_AGENT_STEPS = 7
    }

    private val memoryManager = DanaMemoryManager(context)
    private val callManager = DanaCallManager(context)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun processUserCommand(
        userCommand: String,
        onSpeak: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val cleanCmd = convertPersianDigitsToEnglish(userCommand.trim())
        if (cleanCmd.isEmpty()) return@withContext

        Log.i(TAG, "فرمان دریافت شد: $cleanCmd")

        val localResult = tryFastAndMessengerExecution(cleanCmd, onSpeak)
        if (localResult != null) {
            onSpeak(localResult)
            return@withContext
        }

        val prefs = context.getSharedPreferences("dana_prefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("gemini_api_key", "")?.trim().orEmpty()

        if (apiKey.isEmpty()) {
            onSpeak("برای استفاده از هوش مصنوعی پیشرفته و بینایی صفحه، لطفاً کلید جمینای را در صفحه اصلی برنامه دانا وارد کنید.")
            return@withContext
        }

        val actionHistory = mutableListOf<String>()
        val needsVision = cleanCmd.contains("عکس") || cleanCmd.contains("تصویر") ||
            cleanCmd.contains("رسید") || cleanCmd.contains("شماره کارت") ||
            cleanCmd.contains("ببین") || cleanCmd.contains("صفحه")

        for (step in 1..MAX_AGENT_STEPS) {
            val a11y = DanaAccessibilityService.instance
            val screenDump = a11y?.dumpScreenHierarchy(maxNodes = 75) ?: "[]"
            val screenshotBase64 = if ((step == 1 && needsVision) || screenDump.length < 30) {
                a11y?.captureScreenBase64()
            } else null

            val decision = askGeminiForNextAction(
                apiKey = apiKey,
                userGoal = cleanCmd,
                screenHierarchyJson = screenDump,
                screenshotBase64 = screenshotBase64,
                previousSteps = actionHistory
            )

            if (decision == null) {
                onSpeak("متاسفانه در ارتباط با سرور هوش مصنوعی خطایی رخ داد.")
                return@withContext
            }

            val actionType = decision.optString("action", "FINISH").uppercase()
            val target = decision.optString("target", "")
            val value = decision.optString("value", "")
            val x = decision.optDouble("x", -1.0).toFloat()
            val y = decision.optDouble("y", -1.0).toFloat()
            val speech = decision.optString("speech", "")

            if (speech.isNotBlank()) {
                onSpeak(speech)
            }

            if (actionType == "FINISH" || actionType == "DONE") {
                return@withContext
            }

            val success = executeSingleAction(actionType, target, value, x, y)
            actionHistory.add("Step $step: $actionType(target='$target', value='$value') -> success=$success")

            if (decision.optBoolean("is_final", false)) {
                return@withContext
            }

            delay(1800)
        }
    }

    private suspend fun tryFastAndMessengerExecution(
        cmd: String,
        onSpeak: (String) -> Unit
    ): String? {
        val c = cmd.lowercase()
        val a11y = DanaAccessibilityService.instance
        val prefs = context.getSharedPreferences("dana_prefs", Context.MODE_PRIVATE)

        if (c.contains("شب بخیر") || c.contains("میخوام بخوابم")) {
            systemExecutor.setRingerMode("SILENT")
            systemExecutor.setFlashlight(false)
            systemExecutor.setAlarm(7, 0, "بیدارباش صبحگاهی دانا")
            return "شب بخیر! گوشی را بی‌صدا کردم و زنگ بیدارباش را برای ساعت ۷ صبح تنظیم کردم."
        }
        if (c.contains("صبح بخیر")) {
            systemExecutor.setRingerMode("NORMAL")
            val timeStr = PersianCalendarUtils.getCurrentTimeSpeech()
            val dateStr = PersianCalendarUtils.getCurrentJalaliDateSpeech()
            val batStr = PersianCalendarUtils.getBatteryStatusSpeech(context)
            return "صبح بخیر! $dateStr $timeStr $batStr"
        }
        if (c.contains("حالت رانندگی رو روشن کن") || c.contains("حالت رانندگی فعال")) {
            prefs.edit().putBoolean("driving_mode_enabled", true).apply()
            systemExecutor.setVolumeLevel(85, "MUSIC")
            return "حالت رانندگی فعال شد. از این لحظه تمام پیام‌های دریافتی را با صدای بلند برایتان می‌خوانم."
        }
        if (c.contains("حالت رانندگی رو خاموش کن") || c.contains("حالت رانندگی غیرفعال")) {
            prefs.edit().putBoolean("driving_mode_enabled", false).apply()
            return "حالت رانندگی غیرفعال شد."
        }

        val rememberRegex = Regex("(?:یادت بمونه|به خاطر بسپار|ذخیره کن)\\s+(?:که\\s+)?(.+?)\\s+(?:من\\s+)?(?:برابر با|هست|است|:)\\s*(.+)")
        val remMatch = rememberRegex.find(cmd)
        if (remMatch != null) {
            val key = remMatch.groupValues[1].trim()
            val value = remMatch.groupValues[2].trim()
            memoryManager.rememberFact(key, value)
            return "در حافظه‌ام ذخیره کردم که $key شما $value است."
        }

        val recallTypeMatch = Regex("(.+?)\\s+من\\s+(?:رو|را)\\s+(?:اینجا\\s+)?(?:تایپ کن|بنویس|وارد کن)").find(c)
        if (recallTypeMatch != null) {
            val key = recallTypeMatch.groupValues[1].trim()
            val savedVal = memoryManager.recallFact(key)
            if (savedVal != null) {
                a11y?.inputText(savedVal)
                systemExecutor.copyToClipboard(savedVal, key)
                return "$key شما یعنی $savedVal تایپ و کپی شد."
            }
        }
        val recallAskMatch = Regex("(.+?)\\s+من\\s+(?:چنده|چیه|چیست)\\s*\\??").find(c)
        if (recallAskMatch != null) {
            val key = recallAskMatch.groupValues[1].trim()
            val savedVal = memoryManager.recallFact(key)
            if (savedVal != null) {
                systemExecutor.copyToClipboard(savedVal, key)
                return "$key شما $savedVal است و در کلیپ‌بورد هم کپی شد."
            }
        }

        if (c.contains("رمز پویا") || c.contains("رمز دوم")) {
            val otp = DanaNotificationListener.lastExtractedOtp
            return if (otp != null) {
                a11y?.inputText(otp, "رمز")
                systemExecutor.copyToClipboard(otp, "OTP")
                "آخرین رمز پویا دریافت‌شده $otp است و در کادر رمز وارد شد."
            } else {
                "هنوز پیامک رمز پویایی دریافت نشده است."
            }
        }

        if (c.contains("جواب بده") || c.contains("تماس رو وصل کن") || c.contains("پاسخ بده")) {
            callManager.answerRingingCall(enableSpeaker = true)
            return "تماس وصل شد."
        }
        if (c.contains("رد تماس") || c.contains("تماس رو قطع کن") || c.contains("قطع کن")) {
            if (callManager.endOrRejectCall()) {
                return "تماس قطع شد."
            }
        }
        if (c.contains("بذار روی بلندگو") || c.contains("بلندگو رو روشن کن")) {
            callManager.setSpeakerphone(true)
            return "بلندگوی مکالمه روشن شد."
        }

        if (c.contains("مسیریابی") || c.contains("با نشان برو") || c.contains("با بلد برو") || c.contains("روی نقشه برو")) {
            val navRegex = Regex("(?:با نشان |با بلد |با گوگل مپ )?(?:مسیریابی کن |برو )?(?:به سمت |به طرف |به )(.+)")
            val match = navRegex.find(c)
            val destination = match?.groupValues?.get(1)?.trim() ?: c.replace("مسیریابی", "").trim()
            val preferredMap = when {
                c.contains("نشان") -> "نشان"
                c.contains("بلد") -> "بلد"
                else -> "نشان"
            }
            if (systemExecutor.startNavigation(destination, preferredMap)) {
                return "در حال باز کردن نقشه و مسیریابی به سمت $destination"
            }
        }

        if (c.contains("ساعت چنده") || c.contains("ساعت چند است") || c == "ساعت") {
            return PersianCalendarUtils.getCurrentTimeSpeech()
        }
        if (c.contains("امروز چندمه") || c.contains("تاریخ امروز") || c.contains("چند شنبه است") || c.contains("چندم ماهه")) {
            return PersianCalendarUtils.getCurrentJalaliDateSpeech()
        }
        if (c.contains("باتری") || c.contains("شارژ گوشی")) {
            return PersianCalendarUtils.getBatteryStatusSpeech(context)
        }

        if (c.contains("پیام‌های جدید") || c.contains("اعلان‌ها رو بخون") || c.contains("پیام جدید دارم")) {
            return DanaNotificationListener.getLatestMessagesSpeech()
        }
        if (c.contains("صفحه رو بخون") || c.contains("صفحه رو برام بخون") || c.contains("توی صفحه چی نوشته")) {
            return a11y?.readVisibleScreenText() ?: "لطفاً سرویس دسترسی‌پذیری دانا را فعال کنید."
        }

        val messengerNames = listOf("ایتا", "واتساپ", "واتس‌اپ", "تلگرام", "روبیکا", "بله", "سروش")
        val matchedMessenger = messengerNames.firstOrNull { c.contains(it) }
        if (matchedMessenger != null && (c.contains("پیام") || c.contains("بفرست") || c.contains("بگو") || c.contains("بنویس"))) {
            val msgRegex = Regex("(?:در |توی |با )?$matchedMessenger (?:به |برای )(.+?) (?:پیام بفرست|پیام بده|بفرست|بگو|بنویس)\\s*(.+)")
            val match = msgRegex.find(cmd)
            if (match != null) {
                val contactName = match.groupValues[1].trim()
                val messageText = match.groupValues[2].removePrefix("که ").trim()
                return automateMessengerSend(matchedMessenger, contactName, messageText, onSpeak)
            }
        }

        if (c.contains("پیامک") || c.contains("اس ام اس") || c.contains("اس‌ام‌اس") || c.contains("sms")) {
            val smsRegex = Regex("(?:به |برای )(.+?) (?:پیامک|اس ام اس|اس‌ام‌اس|sms) (?:بفرست|بده|بزن)(?: و بگو| بگو| که)?\\s*(.+)")
            val match = smsRegex.find(c)
            if (match != null) {
                val contact = match.groupValues[1].trim()
                val body = match.groupValues[2].trim()
                return if (systemExecutor.sendSms(contact, body)) {
                    "پیامک شما با متن «$body» برای $contact ارسال شد."
                } else {
                    "مخاطب $contact در دفترچه تلفن پیدا نشد."
                }
            }
        }

        val callMatch = Regex("(?:به |با )(.+?) (?:زنگ بزن|تماس بگیر)").find(c)
        if (callMatch != null && matchedMessenger == null) {
            val contact = callMatch.groupValues[1].trim()
            return if (systemExecutor.makePhoneCall(contact)) {
                "در حال برقراری تماس با $contact"
            } else {
                "شماره‌ای برای $contact در مخاطبین پیدا نشد."
            }
        }

        if (c.contains("چراغ قوه") || c.contains("چراغ‌قوه") || c.contains("فلش")) {
            val turnOn = !c.contains("خاموش")
            return if (systemExecutor.setFlashlight(turnOn)) {
                if (turnOn) "چراغ‌قوه روشن شد." else "چراغ‌قوه خاموش شد."
            } else "خطا در دسترسی به چراغ‌قوه."
        }
        if (c.contains("سایلنت") || c.contains("بی‌صدا") || c.contains("بی صدا")) {
            systemExecutor.setRingerMode("SILENT")
            return "گوشی به حالت بی‌صدا رفت."
        }
        if (c.contains("ویبره")) {
            systemExecutor.setRingerMode("VIBRATE")
            return "گوشی روی حالت ویبره قرار گرفت."
        }
        val volMatch = Regex("صدا.*?(\\d+)").find(c)
        if (volMatch != null) {
            val pct = volMatch.groupValues[1].toIntOrNull() ?: 50
            systemExecutor.setVolumeLevel(pct)
            return "صدای گوشی روی $pct درصد تنظیم شد."
        }
        if (c.contains("آهنگ بعدی") || c.contains("موزیک بعدی")) {
            systemExecutor.controlMedia("NEXT")
            return "آهنگ بعدی پخش شد."
        }
        if (c.contains("آهنگ قبلی") || c.contains("موزیک قبلی")) {
            systemExecutor.controlMedia("PREVIOUS")
            return "آهنگ قبلی پخش شد."
        }
        if (c.contains("موزیک رو قطع کن") || c.contains("آهنگ رو متوقف کن")) {
            systemExecutor.controlMedia("PAUSE")
            return "پخش متوقف شد."
        }

        if (c == "برگرد" || c == "بازگشت" || c == "عقب") {
            a11y?.executeGlobalAction("BACK")
            return "برگشتم."
        }
        if (c.contains("صفحه اصلی") || c == "برو خانه" || c == "هوم") {
            a11y?.executeGlobalAction("HOME")
            return "رفتم به صفحه اصلی."
        }
        if (c.contains("اسکرین شات") || c.contains("اسکرین‌شات") || c.contains("عکس از صفحه")) {
            return if (a11y?.executeGlobalAction("SCREENSHOT") == true) {
                "اسکرین‌شات گرفته شد."
            } else "برای اسکرین‌شات لطفاً دسترسی‌پذیری دانا را فعال کنید."
        }
        if (c.contains("قفل صفحه") || c.contains("صفحه رو قفل کن")) {
            a11y?.executeGlobalAction("LOCK_SCREEN")
            return "صفحه قفل شد."
        }
        if (c.contains("اسکرول پایین") || c == "برو پایین" || c == "صفحه بعدی") {
            a11y?.scrollScreen("down")
            return "صفحه به پایین اسکرول شد."
        }
        if (c.contains("اسکرول بالا") || c == "برو بالا") {
            a11y?.scrollScreen("up")
            return "صفحه به بالا اسکرول شد."
        }
        val openMatch = Regex("^(?:برنامه )?(.+?) (?:رو |را )?باز کن$").find(c)
        if (openMatch != null && !c.contains(" و ")) {
            val appName = openMatch.groupValues[1].trim()
            if (systemExecutor.openApp(appName)) {
                return "در حال باز کردن $appName"
            }
        }
        val clickMatch = Regex("روی (.+?) (?:کلیک کن|بزن)").find(c)
        if (clickMatch != null && a11y != null) {
            val targetText = clickMatch.groupValues[1].trim()
            if (a11y.clickByText(targetText)) {
                return "روی $targetText کلیک شد."
            }
        }
        val typeMatch = Regex("(?:بنویس|تایپ کن)\\s+(.+)").find(cmd)
        if (typeMatch != null && a11y != null) {
            val textToType = typeMatch.groupValues[1].trim()
            if (a11y.inputText(textToType)) {
                return "متن مورد نظر نوشته شد."
            }
        }

        return null
    }

    private suspend fun automateMessengerSend(
        appName: String,
        contactName: String,
        messageText: String,
        onSpeak: (String) -> Unit
    ): String {
        val notifListener = DanaNotificationListener.instance
        if (notifListener != null && notifListener.replyToSenderIfAvailable(contactName, messageText)) {
            return "پیام شما به $contactName در $appName فوراً ارسال شد."
        }

        val a11y = DanaAccessibilityService.instance
            ?: return "برای ارسال پیام خودکار در $appName، لطفاً سرویس دسترسی‌پذیری دانا را فعال کنید."

        if (!systemExecutor.openApp(appName)) {
            return "برنامه $appName روی گوشی پیدا نشد."
        }

        onSpeak("در حال باز کردن $appName و ارسال پیام به $contactName")
        delay(2200)

        var chatOpened = a11y.clickByText(contactName)
        if (!chatOpened) {
            a11y.clickSearchButtonInMessenger()
            delay(900)
            a11y.inputText(contactName)
            delay(1400)
            chatOpened = a11y.clickByText(contactName)
        }

        if (!chatOpened) {
            return "برنامه $appName باز شد اما گفتگوی $contactName در صفحه پیدا نشد."
        }

        delay(1200)
        val typed = a11y.inputText(messageText)
        delay(600)
        val sent = a11y.clickSendButtonInMessenger()

        return if (typed && sent) {
            "پیام شما با موفقیت در $appName برای $contactName ارسال شد."
        } else if (typed) {
            "متن پیام در $appName برای $contactName تایپ شد؛ کافی است دکمه ارسال را بزنید."
        } else {
            "گفتگوی $contactName در $appName باز شد."
        }
    }

    private fun executeSingleAction(
        action: String,
        target: String,
        value: String,
        x: Float,
        y: Float
    ): Boolean {
        val a11y = DanaAccessibilityService.instance

        return when (action) {
            "OPEN_APP" -> systemExecutor.openApp(target)
            "NAVIGATE" -> systemExecutor.startNavigation(target, value)
            "COPY_CLIPBOARD" -> systemExecutor.copyToClipboard(value.ifBlank { target })
            "REMEMBER_FACT" -> {
                memoryManager.rememberFact(target, value)
                true
            }
            "ANSWER_CALL" -> callManager.answerRingingCall(enableSpeaker = true)
            "END_CALL" -> callManager.endOrRejectCall()
            "CLICK_TEXT" -> a11y?.clickByText(target) ?: false
            "LONG_PRESS" -> a11y?.longPressByText(target) ?: false
            "TAP_XY" -> a11y?.tapCoordinates(x, y) ?: false
            "TYPE_TEXT" -> a11y?.inputText(textToType = value, targetFieldQuery = target.ifBlank { null }) ?: false
            "CLICK_SEND" -> a11y?.clickSendButtonInMessenger() ?: false
            "SCROLL" -> a11y?.scrollScreen(target.ifBlank { "down" }) ?: false
            "GLOBAL_ACTION" -> a11y?.executeGlobalAction(target) ?: false
            "CALL" -> systemExecutor.makePhoneCall(target)
            "SEND_SMS" -> systemExecutor.sendSms(target, value)
            "SET_ALARM" -> {
                val parts = target.split(":")
                val h = parts.getOrNull(0)?.toIntOrNull() ?: 8
                val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
                systemExecutor.setAlarm(h, m, value.ifBlank { "دانا" })
            }
            "FLASHLIGHT" -> systemExecutor.setFlashlight(target.equals("ON", ignoreCase = true))
            "SET_VOLUME" -> systemExecutor.setVolumeLevel(value.toIntOrNull() ?: 50, target)
            "MEDIA_CONTROL" -> systemExecutor.controlMedia(target)
            "OPEN_SETTINGS" -> systemExecutor.openSettingsSection(target)
            "SEARCH_WEB" -> systemExecutor.searchWeb(target)
            else -> false
        }
    }

    private fun convertPersianDigitsToEnglish(input: String): String {
        val persianDigits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
        val arabicDigits = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')
        var out = input
        for (i in 0..9) {
            out = out.replace(persianDigits[i], ('0' + i)).replace(arabicDigits[i], ('0' + i))
        }
        return out
    }

    private fun askGeminiForNextAction(
        apiKey: String,
        userGoal: String,
        screenHierarchyJson: String,
        screenshotBase64: String?,
        previousSteps: List<String>
    ): JSONObject? {
        val systemPrompt = buildString {
            appendLine("تو «دانا» (Dana) هستی، یک دستیار هوش مصنوعی صوتی فارسی و کاربر مجازی با قابلیت بینایی ماشین در گوشی اندروید.")
            appendLine("وظیفه تو این است که درخواست صوتی کاربر را به صورت گام‌به‌گام روی گوشی اجرا کنی.")
            appendLine("تو لیست المان‌های صفحه فعلی (Screen Hierarchy)، در صورت وجود تصویر اسکرین‌شات صفحه و همچنین حافظه شخصی کاربر را می‌بینی و باید فقط یک JSON معتبر برای گام بعدی برگردانی.")
            appendLine("اکشن‌های مجاز (فیلد action): OPEN_APP, NAVIGATE, COPY_CLIPBOARD, REMEMBER_FACT, ANSWER_CALL, END_CALL, CLICK_TEXT, LONG_PRESS, TAP_XY, TYPE_TEXT, CLICK_SEND, SCROLL, GLOBAL_ACTION, CALL, SEND_SMS, SET_ALARM, FLASHLIGHT, SET_VOLUME, MEDIA_CONTROL, OPEN_SETTINGS, SEARCH_WEB, FINISH")
            appendLine("فرمت خروجی JSON شامل فیلدهای: action, target, value, x, y, speech, is_final")
        }

        val userPayload = buildString {
            appendLine("زمان فعلی: ${PersianCalendarUtils.getCurrentTimeSpeech()} - ${PersianCalendarUtils.getCurrentJalaliDateSpeech()}")
            appendLine("حافظه شخصی ذخیره‌شده کاربر: ${memoryManager.dumpAllMemoryAsText()}")
            appendLine("آخرین رمز پویا بانکی: ${DanaNotificationListener.lastExtractedOtp ?: "ندارد"}")
            appendLine("درخواست کاربر: $userGoal")
            appendLine("گام‌های انجام‌شده تا الان: ${if (previousSteps.isEmpty()) "هیچ" else previousSteps.joinToString(" | ")}")
            appendLine("المان‌های صفحه فعلی گوشی (JSON):")
            append(screenHierarchyJson)
        }

        val userParts = JSONArray().apply {
            put(JSONObject().put("text", userPayload))
            if (!screenshotBase64.isNullOrBlank()) {
                put(JSONObject().apply {
                    put("inlineData", JSONObject().apply {
                        put("mimeType", "image/jpeg")
                        put("data", screenshotBase64)
                    })
                })
            }
        }

        val requestBodyJson = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            })
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", userParts)
            }))
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
                put("temperature", 0.1)
            })
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(requestBodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Gemini HTTP Error: ${response.code}")
                    return null
                }
                val bodyStr = response.body?.string() ?: return null
                val root = JSONObject(bodyStr)
                val textResponse = root
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .trim()
                JSONObject(textResponse)
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطا در پردازش پاسخ Gemini", e)
            null
        }
    }
}

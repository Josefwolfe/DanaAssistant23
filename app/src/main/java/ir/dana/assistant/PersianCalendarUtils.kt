package ir.dana.assistant

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.util.Calendar

object PersianCalendarUtils {

    private val PERSIAN_MONTHS = arrayOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    )

    private val PERSIAN_WEEKDAYS = mapOf(
        Calendar.SATURDAY to "شنبه",
        Calendar.SUNDAY to "یکشنبه",
        Calendar.MONDAY to "دوشنبه",
        Calendar.TUESDAY to "سه‌شنبه",
        Calendar.WEDNESDAY to "چهارشنبه",
        Calendar.THURSDAY to "پنجشنبه",
        Calendar.FRIDAY to "جمعه"
    )

    fun getCurrentTimeSpeech(): String {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)

        val period = when (hour) {
            in 0..4 -> "بامداد"
            in 5..11 -> "صبح"
            in 12..15 -> "بعدازظهر"
            in 16..19 -> "عصر"
            else -> "شب"
        }

        val displayHour = if (hour % 12 == 0) 12 else hour % 12
        return if (minute == 0) {
            "ساعت $displayHour $period است."
        } else {
            "ساعت $displayHour و $minute دقیقه $period است."
        }
    }

    fun getCurrentJalaliDateSpeech(): String {
        val cal = Calendar.getInstance()
        val gy = cal.get(Calendar.YEAR)
        val gm = cal.get(Calendar.MONTH) + 1
        val gd = cal.get(Calendar.DAY_OF_MONTH)
        val weekday = PERSIAN_WEEKDAYS[cal.get(Calendar.DAY_OF_WEEK)] ?: ""

        val (jy, jm, jd) = gregorianToJalali(gy, gm, gd)
        val monthName = PERSIAN_MONTHS.getOrElse(jm - 1) { "" }

        return "امروز $weekday، $jd $monthName سال $jy خورشیدی است."
    }

    fun getBatteryStatusSpeech(context: Context): String {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, intentFilter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        if (level < 0 || scale <= 0) return "اطلاعات باتری در دسترس نیست."
        val pct = (level * 100) / scale
        val chargingText = if (isCharging) "و گوشی در حال شارژ است" else "شارژ دارد"
        return "باتری گوشی شما $pct درصد $chargingText."
    }

    private fun gregorianToJalali(gy: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
        val gDaysInMonth = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + (365 * gy) + ((gy2 + 3) / 4) - ((gy2 + 99) / 100) + ((gy2 + 399) / 400) + gd + gDaysInMonth[gm - 1]
        var jy = -1595 + (33 * (days / 12053))
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm: Int
        val jd: Int
        if (days < 186) {
            jm = 1 + (days / 31)
            jd = 1 + (days % 31)
        } else {
            jm = 7 + ((days - 186) / 30)
            jd = 1 + ((days - 186) % 30)
        }
        return Triple(jy, jm, jd)
    }
}

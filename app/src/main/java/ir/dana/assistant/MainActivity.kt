package ir.dana.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var apiKeyInput: EditText
    private lateinit var wakeWordCheckBox: CheckBox
    private lateinit var drivingModeCheckBox: CheckBox
    private lateinit var autoOtpCheckBox: CheckBox
    private lateinit var testCommandInput: EditText
    private var persianSpeaker: PersianVoiceSpeaker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        if (intent?.action == Intent.ACTION_ASSIST || intent?.action == Intent.ACTION_VOICE_COMMAND) {
            val voiceIntent = Intent(this, DanaVoiceService::class.java).apply {
                action = DanaVoiceService.ACTION_TRIGGER_LISTEN
            }
            ContextCompat.startForegroundService(this, voiceIntent)
            finish()
            return
        }

        persianSpeaker = PersianVoiceSpeaker(this)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        setContentView(buildDashboardUi())
        updatePermissionsStatus()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            ContextCompat.startForegroundService(this, Intent(this, DanaVoiceService::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        if (::statusTextView.isInitialized) {
            updatePermissionsStatus()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        persianSpeaker?.shutdown()
    }

    @SuppressLint("BatteryLife")
    private fun buildDashboardUi(): View {
        val prefs = getSharedPreferences("dana_prefs", Context.MODE_PRIVATE)

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F5F7FA"))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
        }

        val title = TextView(this).apply {
            text = "دستیار هوشمند صوتی «دانا» (نسخه ۴) 🎙️"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#0D47A1"))
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "شنود دائمی در پس‌زمینه بدون نیاز به زدن دکمه — کافی است بگویید: «دانا ...»"
            textSize = 13f
            setTextColor(Color.parseColor("#2E7D32"))
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 32)
        }
        container.addView(title)
        container.addView(subtitle)

        statusTextView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#263238"))
            setPadding(36, 36, 36, 36)
            background = createCardBackground("#FFFFFF")
        }
        container.addView(statusTextView)

        container.addView(createActionButton("۱. فعال‌سازی سرویس دسترسی‌پذیری (کاربر مجازی)", "#1E88E5") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "از لیست سرویس‌ها «کاربر مجازی دانا» را فعال کنید", Toast.LENGTH_LONG).show()
        })

        container.addView(createActionButton("۲. فعال‌سازی خواننده اعلان‌ها و رمز پویا بانک", "#3949AB") {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            Toast.makeText(this, "دسترسی «خواننده پیام‌ها، رمز پویا و اعلان‌های دانا» را روشن کنید", Toast.LENGTH_LONG).show()
        })

        container.addView(createActionButton("۳. معافیت از بهینه‌سازی باتری (برای شنود ۲۴ ساعته بدون توقف)", "#00897B") {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "معافیت باتری برای فعالیت دائمی در پس‌زمینه فعال است ✅", Toast.LENGTH_SHORT).show()
            }
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })

        container.addView(createActionButton("۴. اعطای مجوزهای میکروفون، تماس، پاسخ تماس و پیامک", "#5E35B1") {
            requestRuntimePermissions()
        })

        val apiLabel = TextView(this).apply {
            text = "کلید هوش مصنوعی و بینایی ماشین (Gemini API Key):"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#263238"))
            setPadding(0, 36, 0, 12)
        }
        apiKeyInput = EditText(this).apply {
            hint = "AIzaSy..."
            setText(prefs.getString("gemini_api_key", ""))
            setPadding(32, 28, 32, 28)
            background = createCardBackground("#FFFFFF")
        }

        wakeWordCheckBox = CheckBox(this).apply {
            text = "شنود دائمی و خودکار کلمه «دانا» در پس‌زمینه (بدون نیاز به زدن دکمه)"
            isChecked = prefs.getBoolean("wake_word_enabled", true)
            setPadding(8, 16, 8, 8)
        }
        autoOtpCheckBox = CheckBox(this).apply {
            text = "استخراج، کپی و تایپ خودکار رمز پویا بانک به محض دریافت پیامک"
            isChecked = prefs.getBoolean("auto_otp_enabled", true)
            setPadding(8, 8, 8, 8)
        }
        drivingModeCheckBox = CheckBox(this).apply {
            text = "حالت رانندگی (خواندن خودکار پیام‌های دریافتی ایتا، واتساپ، تلگرام و پیامک با صدا)"
            isChecked = prefs.getBoolean("driving_mode_enabled", false)
            setPadding(8, 8, 8, 16)
        }

        container.addView(apiLabel)
        container.addView(apiKeyInput)
        container.addView(wakeWordCheckBox)
        container.addView(autoOtpCheckBox)
        container.addView(drivingModeCheckBox)

        container.addView(createActionButton("🚀 ذخیره و فعال‌سازی دائمی دانا در پس‌زمینه", "#2E7D32") {
            prefs.edit()
                .putString("gemini_api_key", apiKeyInput.text.toString().trim())
                .putBoolean("wake_word_enabled", wakeWordCheckBox.isChecked)
                .putBoolean("auto_otp_enabled", autoOtpCheckBox.isChecked)
                .putBoolean("driving_mode_enabled", drivingModeCheckBox.isChecked)
                .apply()

            val serviceIntent = Intent(this, DanaVoiceService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
            persianSpeaker?.speakPersian("دانا در پس‌زمینه فعال شد. هر زمان کاری داشتید فقط بگویید دانا.")
            Toast.makeText(this, "دانا در پس‌زمینه فعال شد! می‌توانید برنامه را ببندید و فقط با صدا فرمان بدهید.", Toast.LENGTH_LONG).show()
            updatePermissionsStatus()
        })

        val testLabel = TextView(this).apply {
            text = "تست سریع فرمان فارسی:"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#263238"))
            setPadding(0, 40, 0, 12)
        }
        testCommandInput = EditText(this).apply {
            hint = "فرمان خود را بنویسید..."
            setText("ساعت چنده")
            setPadding(32, 28, 32, 28)
            background = createCardBackground("#FFFFFF")
        }
        container.addView(testLabel)
        container.addView(testCommandInput)

        container.addView(createActionButton("اجرا و پخش پاسخ صوتی فارسی 🔊", "#FB8C00") {
            val cmd = testCommandInput.text.toString().trim()
            if (cmd.isNotEmpty()) {
                val agent = DanaAiAgent(this, SystemActionExecutor(this))
                CoroutineScope(Dispatchers.Main).launch {
                    agent.processUserCommand(cmd) { reply ->
                        runOnUiThread {
                            persianSpeaker?.speakPersian(reply)
                            Toast.makeText(this@MainActivity, "دانا: $reply", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        })

        scrollView.addView(container)
        return scrollView
    }

    private fun updatePermissionsStatus() {
        val a11yEnabled = DanaAccessibilityService.isRunning()
        val notifEnabled = DanaNotificationListener.instance != null
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryUnrestricted = pm.isIgnoringBatteryOptimizations(packageName)
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        statusTextView.text = buildString {
            appendLine("📊 وضعیت فعالیت دائمی «دانا» در پس‌زمینه:")
            appendLine("• سرویس کاربر مجازی و بینایی صفحه: ${if (a11yEnabled) "فعال ✅" else "غیرفعال ❌"}")
            appendLine("• سرویس خواندن اعلان‌ها و رمز پویا بانک: ${if (notifEnabled) "فعال ✅" else "غیرفعال ❌"}")
            appendLine("• فعالیت نامحدود پس‌زمینه (بدون توقف باتری): ${if (batteryUnrestricted) "فعال ✅" else "نیاز به تایید ❌"}")
            append("• شنود دائمی میکروفون (کلمه «دانا»): ${if (micGranted) "فعال 🟢" else "غیرفعال ❌"}")
        }
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissions.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
    }

    private fun createActionButton(title: String, colorHex: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 14f
            background = createCardBackground(colorHex, cornerRadius = 32f)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 18, 0, 0)
            }
            layoutParams = params
            setOnClickListener { onClick() }
        }
    }

    private fun createCardBackground(colorHex: String, cornerRadius: Float = 28f): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            this.cornerRadius = cornerRadius
            setColor(Color.parseColor(colorHex))
        }
    }
}

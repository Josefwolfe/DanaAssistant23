package ir.dana.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // طراحی رابط کاربری مدرن و تاریک (Dark Mode) به صورت مستقیم در کد
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(64, 64, 64, 64)
        }

        val titleText = TextView(this).apply {
            text = "دستیار هوشمند دانا"
            textSize = 26f
            setTextColor(Color.parseColor("#FFFFFF"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }

        statusText = TextView(this).apply {
            text = "برای شروع، مجوزها را تایید کرده و دکمه فعال‌سازی را بزنید."
            textSize = 15f
            setTextColor(Color.parseColor("#B0B0B0"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 72)
        }

        val startButton = Button(this).apply {
            text = "فعال‌سازی دستیار در پس‌زمینه"
            textSize = 16f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E88E5"))
                cornerRadius = 32f
            }
            setPadding(48, 32, 48, 32)
            setOnClickListener {
                checkPermissionsAndStart()
            }
        }

        val stopButton = Button(this).apply {
            text = "توقف کامل دستیار"
            textSize = 15f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E53935"))
                cornerRadius = 32f
            }
            setPadding(48, 28, 48, 28)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 40
            }
            layoutParams = params
            setOnClickListener {
                stopService(Intent(this@MainActivity, VoiceService::class.java))
                statusText.text = "وضعیت: دستیار متوقف شد."
                Toast.makeText(this@MainActivity, "دستیار دانا غیرفعال شد", Toast.LENGTH_SHORT).show()
            }
        }

        rootLayout.addView(titleText)
        rootLayout.addView(statusText)
        rootLayout.addView(startButton)
        rootLayout.addView(stopButton)

        setContentView(rootLayout)

        // بررسی خودکار مجوزها به محض باز شدن برنامه
        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissions(missingPermissions.toTypedArray(), 100)
        } else {
            startDanaService()
        }
    }

    private fun startDanaService() {
        val serviceIntent = Intent(this, VoiceService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        statusText.text = "وضعیت: دانا فعال است و در پس‌زمینه گوش می‌دهد.\nکافیست بگویید: «دانا تنظیمات را باز کن»"
        Toast.makeText(this, "دستیار دانا فعال شد", Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            startDanaService()
        }
    }
}

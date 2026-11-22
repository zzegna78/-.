package ai.venice.aimdrag

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView
    private lateinit var shizukuStatus: TextView

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(this, InjectionService::class.java))
            .daemon(false)
            .processNameSuffix("inj")
            .debuggable(false)
            .version(2)
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shizukuStatus.text = "Инжект готов"
            shizukuStatus.setTextColor(0xFF22C55E.toInt())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shizukuStatus.text = "Инжект отвалился"
            shizukuStatus.setTextColor(0xFFEF4444.toInt())
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        prefs = getSharedPreferences("aimdrag", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }

        root.addView(TextView(this).apply { text = "AimDrag 2"; textSize = 24f })
        root.addView(TextView(this).apply {
            text = "Живой драг через инжект ввода. Нужен запущенный Shizuku."
            textSize = 13f
            setPadding(0, dp(10), 0, dp(16))
        })

        status = TextView(this).apply { textSize = 13f }
        shizukuStatus = TextView(this).apply { textSize = 13f; text = "Shizuku не подключён" }
        root.addView(status)
        root.addView(shizukuStatus)

        root.addView(Button(this).apply {
            text = "1. Спец. возможности"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "2. Разрешение Shizuku"
            setOnClickListener { askShizuku() }
        })

        root.addView(toggle("Показывать направляющие", "guides", true))
        root.addView(toggle("Ассист драга", "assist", false))
        root.addView(slider("Множитель", "mult", 1.0f, 3.0f, 2.0f))
        root.addView(slider("Размер кнопки огня, %", "fire_pct", 18f, 34f, 24f))
        root.addView(slider("Отступ справа, %", "off_x", 0f, 6f, 1.5f))
        root.addView(slider("Отступ снизу, %", "off_y", 0f, 12f, 4f))

        root.addView(TextView(this).apply {
            text = "Порядок: включить Shizuku → разрешение → спец. возможности → ассист. Красный круг должен совпасть с кнопкой огня в HUD."
            textSize = 12f
            setPadding(0, dp(16), 0, 0)
        })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val a11y = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains(packageName) == true
        status.text = if (a11y) "Служба включена" else "Служба выключена"
        status.setTextColor(if (a11y) 0xFF22C55E.toInt() else 0xFFEF4444.toInt())

        if (Shizuku.pingBinder()) {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bindInject()
            } else {
                shizukuStatus.text = "Shizuku есть, нужно разрешение"
            }
        } else {
            shizukuStatus.text = "Shizuku не запущен"
        }
    }

    private fun askShizuku() {
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, "Сначала запусти Shizuku", Toast.LENGTH_LONG).show()
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            bindInject()
        } else {
            Shizuku.requestPermission(7)
        }
    }

    private fun bindInject() {
        runCatching {
            Shizuku.bindUserService(args, conn)
            shizukuStatus.text = "Инжект готов"
            shizukuStatus.setTextColor(0xFF22C55E.toInt())
        }
    }

    private fun toggle(label: String, key: String, def: Boolean): ViewGroup {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(Switch(this).apply {
            isChecked = prefs.getBoolean(key, def)
            setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(key, v).apply() }
        })
        return row
    }

    private fun slider(label: String, key: String, min: Float, max: Float, def: Float): ViewGroup {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val caption = TextView(this).apply { textSize = 13f }
        fun paint(v: Float) { caption.text = "$label: " + String.format("%.1f", v) }
        paint(prefs.getFloat(key, def))
        box.addView(caption)
        box.addView(SeekBar(this).apply {
            this.max = ((max - min) * 10).toInt()
            progress = ((prefs.getFloat(key, def) - min) * 10).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = min + p / 10f
                    paint(v)
                    prefs.edit().putFloat(key, v).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })
        box.setPadding(0, dp(12), 0, 0)
        return box
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

package com.stefcuber.petonscreen

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.Manifest
import android.content.pm.PackageManager
import android.widget.*
import android.graphics.Color
import android.view.Gravity
import java.io.File

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("pet", MODE_PRIVATE) }
    private lateinit var nameInput: EditText
    private lateinit var personality: Spinner
    private lateinit var colorSpinner: Spinner

    companion object {
        private const val PICK_IDLE = 101
        private const val PICK_WALK = 102
        private const val PICK_SLEEP = 103
        private const val PICK_TOUCH = 104
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(28, 31, 42)
        window.navigationBarColor = Color.rgb(28, 31, 42)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
            setBackgroundColor(Color.rgb(20, 23, 32))
        }
        fun label(text: String) = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 8)
        }
        val title = TextView(this).apply {
            text = "🐾 Мой экранный питомец"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 20)
        }
        layout.addView(title)

        layout.addView(label("Имя питомца"))
        nameInput = EditText(this).apply {
            setSingleLine()
            hint = "Например, Тоффи"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setText(prefs.getString("name", "Пиксель"))
        }
        layout.addView(nameInput)

        layout.addView(label("Характер"))
        personality = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Весёлый", "Любопытный", "Ленивый", "Вредный", "Спокойный", "Энергичный"))
            setSelection(prefs.getInt("personality", 1))
        }
        layout.addView(personality)

        layout.addView(label("Цвет запасного персонажа"))
        colorSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Голубой", "Оранжевый", "Зелёный", "Розовый", "Фиолетовый"))
            setSelection(prefs.getInt("color", 0))
        }
        layout.addView(colorSpinner)

        layout.addView(label("Анимации питомца (GIF)"))
        layout.addView(gifButton("Загрузить GIF: обычное состояние", PICK_IDLE, "idle"))
        layout.addView(gifButton("Загрузить GIF: ходьба", PICK_WALK, "walk"))
        layout.addView(gifButton("Загрузить GIF: сон", PICK_SLEEP, "sleep"))
        layout.addView(gifButton("Загрузить GIF: реакция на касание", PICK_TOUCH, "touch"))

        val info = TextView(this).apply {
            text = "Выбирай GIF из файлов телефона. Перетаскивание питомца включает анимацию ходьбы, нажатие — реакцию. Если нужная GIF не загружена, используется обычный персонаж."
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 18, 0, 18)
        }
        layout.addView(info)
        layout.addView(Button(this).apply {
            text = "Показать питомца"
            setOnClickListener { saveAndStart() }
        })
        layout.addView(Button(this).apply {
            text = "Убрать питомца с экрана"
            setOnClickListener { stopService(Intent(this@MainActivity, PetOverlayService::class.java)) }
        })
        layout.addView(TextView(this).apply {
            text = "Для отображения поверх окон Android попросит отдельное разрешение. Пока питомец активен, Android показывает уведомление."
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 18, 0, 0)
        })
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun gifButton(title: String, requestCode: Int, key: String): Button {
        val existing = File(filesDir, "pet_$key.gif").exists()
        return Button(this).apply {
            text = if (existing) "$title ✓" else title
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/gif"
                }
                startActivityForResult(intent, requestCode)
            }
        }
    }

    @Deprecated("Using activity result for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val key = when (requestCode) {
            PICK_IDLE -> "idle"
            PICK_WALK -> "walk"
            PICK_SLEEP -> "sleep"
            PICK_TOUCH -> "touch"
            else -> return
        }
        try {
            contentResolver.openInputStream(data.data!!).use { input ->
                if (input == null) throw IllegalStateException("Не удалось открыть GIF")
                File(filesDir, "pet_$key.gif").outputStream().use { output -> input.copyTo(output) }
            }
            Toast.makeText(this, "GIF добавлена: $key", Toast.LENGTH_SHORT).show()
            // Restart the overlay so the currently displayed animation is refreshed.
            if (Settings.canDrawOverlays(this)) {
                stopService(Intent(this, PetOverlayService::class.java))
                val serviceIntent = Intent(this, PetOverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
                else startService(serviceIntent)
            }
            recreate()
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось загрузить GIF: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveAndStart() {
        prefs.edit()
            .putString("name", nameInput.text.toString().ifBlank { "Пиксель" })
            .putInt("personality", personality.selectedItemPosition)
            .putInt("color", colorSpinner.selectedItemPosition)
            .apply()

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
            Toast.makeText(this, "Включи разрешение и снова нажми «Показать питомца».", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 41)
        }
        val serviceIntent = Intent(this, PetOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
        else startService(serviceIntent)
        Toast.makeText(this, "Питомец отправился гулять по экрану!", Toast.LENGTH_SHORT).show()
    }
}

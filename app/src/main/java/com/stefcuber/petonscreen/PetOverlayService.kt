package com.stefcuber.petonscreen

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.Toast
import java.io.File

class PetOverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var petView: PetView? = null
    private val channelId = "pet_overlay_channel"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(7, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(7, notification)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        petView = PetView(this)
        val params = WindowManager.LayoutParams(
            dp(116), dp(116),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(30); y = dp(180)
        }
        try { windowManager?.addView(petView, params) }
        catch (_: Exception) {
            Toast.makeText(this, "Проверь разрешение «Поверх других приложений».", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "Экранный питомец", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder.setContentTitle("Питомец активен")
            .setContentText("Нажми, чтобы открыть настройки питомца")
            .setSmallIcon(android.R.drawable.star_on).setContentIntent(open).setOngoing(true).build()
    }

    override fun onDestroy() {
        petView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        petView = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private inner class PetView(context: Context) : View(context) {
        private val prefs = getSharedPreferences("pet", MODE_PRIVATE)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val colors = intArrayOf(Color.rgb(75,190,245), Color.rgb(255,151,65),
            Color.rgb(93,205,132), Color.rgb(245,133,190), Color.rgb(174,130,245))
        private var mood = 0
        private var dragging = false
        private var activeAnimation = "idle"
        private var movie: Movie? = null
        private var movieFile: String? = null
        private var movieStart = 0L
        private var touchUntil = 0L

        private fun loadAnimation(kind: String) {
            val file = File(filesDir, "pet_$kind.gif")
            if (!file.exists()) {
                movie = null; movieFile = null; activeAnimation = kind
                return
            }
            if (movieFile == file.absolutePath && activeAnimation == kind && movie != null) return
            try {
                file.inputStream().use { movie = Movie.decodeStream(it) }
                movieFile = file.absolutePath
                activeAnimation = kind
                movieStart = android.os.SystemClock.uptimeMillis()
            } catch (_: Exception) { movie = null; movieFile = null }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val now = android.os.SystemClock.uptimeMillis()
            val wanted = when {
                dragging && File(filesDir, "pet_walk.gif").exists() -> "walk"
                now < touchUntil && File(filesDir, "pet_touch.gif").exists() -> "touch"
                else -> "idle"
            }
            if (wanted != activeAnimation) loadAnimation(wanted)

            val current = movie
            if (current != null) {
                val duration = maxOf(1, current.duration())
                val time = ((now - movieStart) % duration).toInt()
                current.setTime(time)
                val mw = maxOf(1, current.width()).toFloat()
                val mh = maxOf(1, current.height()).toFloat()
                val scale = minOf((width - 8f) / mw, (height - 8f) / mh)
                val save = canvas.save()
                canvas.translate((width - mw * scale) / 2f, (height - mh * scale) / 2f)
                canvas.scale(scale, scale)
                current.draw(canvas, 0f, 0f)
                canvas.restoreToCount(save)
                postInvalidateDelayed(30)
                return
            }

            // Fallback character when no GIF has been assigned.
            val color = colors[prefs.getInt("color", 0).coerceIn(0, colors.lastIndex)]
            val cx = width / 2f; val cy = height / 2f
            paint.color = Color.argb(45,0,0,0)
            canvas.drawOval(RectF(cx-31,cy+29,cx+31,cy+39),paint)
            paint.color = color
            canvas.drawRoundRect(RectF(cx-39,cy-31,cx+39,cy+34),34f,34f,paint)
            val ears = Path().apply {
                moveTo(cx-28,cy-20); lineTo(cx-35,cy-43); lineTo(cx-8,cy-29); close()
                moveTo(cx+28,cy-20); lineTo(cx+35,cy-43); lineTo(cx+8,cy-29); close()
            }
            canvas.drawPath(ears,paint)
            paint.color = Color.WHITE
            canvas.drawCircle(cx-13,cy-5,5f,paint); canvas.drawCircle(cx+13,cy-5,5f,paint)
            paint.color = Color.rgb(35,40,50)
            canvas.drawCircle(cx-12,cy-4,2.5f,paint); canvas.drawCircle(cx+12,cy-4,2.5f,paint)
            canvas.drawCircle(cx,cy+8,3f,paint)
            paint.color = Color.WHITE; paint.textAlign = Paint.Align.CENTER; paint.textSize = 10f
            canvas.drawText((prefs.getString("name","Пиксель") ?: "Пиксель").take(12),cx,height-3f,paint)
        }

        private var downX = 0f; private var downY = 0f
        private var startX = 0; private var startY = 0
        private var moved = false

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val params = layoutParams as? WindowManager.LayoutParams ?: return true
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX=event.rawX; downY=event.rawY; startX=params.x; startY=params.y
                    moved=false; return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx=(event.rawX-downX).toInt(); val dy=(event.rawY-downY).toInt()
                    if (kotlin.math.abs(dx)+kotlin.math.abs(dy)>8) moved=true
                    dragging=moved
                    params.x=startX+dx; params.y=startY+dy
                    windowManager?.updateViewLayout(this,params)
                    invalidate(); return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moved) { mood++; touchUntil=android.os.SystemClock.uptimeMillis()+1800 }
                    dragging=false; invalidate(); return true
                }
            }
            return true
        }
    }
}

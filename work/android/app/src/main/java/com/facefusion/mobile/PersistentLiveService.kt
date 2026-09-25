package com.facefusion.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Foreground lifetime and clean overlay for one existing LiveEngine.
 *
 * The service owns no inference loop. MainActivity binds its one engine to this lifecycle
 * and forwards the already-produced preview bitmap here.
 */
class PersistentLiveService : Service(), LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private val main = Handler(Looper.getMainLooper())
    private lateinit var windows: WindowManager
    private var root: FrameLayout? = null
    private var image: ImageView? = null
    private var controls: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        startForeground(NOTIFICATION_ID, notification())
        createOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        requestClose()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (registry.currentState.isAtLeast(Lifecycle.State.RESUMED))
            registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        if (registry.currentState.isAtLeast(Lifecycle.State.STARTED))
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        root?.let { runCatching { windows.removeView(it) } }
        root = null
        image = null
        controls = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun notification() : android.app.Notification {
        val channelId = "live_engine"
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    channelId, "LiveFusion live engine", NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("LiveFusion is live")
            .setContentText("Camera processing continues in the Persistent Rectangle")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun createOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        windows = getSystemService(WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val width = (150f * density).roundToInt()
        val saved = getSharedPreferences(PREFS, MODE_PRIVATE)
        val lp = WindowManager.LayoutParams(
            width,
            (width * 5f / 3f).roundToInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = saved.getInt("x", (8f * density).roundToInt())
            y = saved.getInt("y", (48f * density).roundToInt())
        }
        params = lp

        val holder = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        val picture = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        holder.addView(
            picture,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xB0000000.toInt())
            visibility = View.GONE
        }
        val open = Button(this).apply {
            text = "Open"
            setOnClickListener {
                actions.visibility = View.GONE
                hideOverlay()
                startActivity(
                    Intent(this@PersistentLiveService, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP,
                    ),
                )
            }
        }
        val close = Button(this).apply {
            text = "Close"
            setOnClickListener { requestClose() }
        }
        actions.addView(open)
        actions.addView(close)
        holder.addView(
            actions,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        installGestures(picture, actions, lp)
        windows.addView(holder, lp)
        root = holder
        image = picture
        controls = actions
    }

    private fun installGestures(
        picture: ImageView, actions: LinearLayout, lp: WindowManager.LayoutParams,
    ) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var taps = 0
        val resetTaps = Runnable { taps = 0 }
        picture.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    if (moved) {
                        val metrics = resources.displayMetrics
                        lp.x = (startX + dx.toInt()).coerceIn(0, (metrics.widthPixels - lp.width).coerceAtLeast(0))
                        lp.y = (startY + dy.toInt()).coerceIn(0, (metrics.heightPixels - lp.height).coerceAtLeast(0))
                        root?.let { windows.updateViewLayout(it, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putInt("x", lp.x).putInt("y", lp.y).apply()
                        taps = 0
                    } else {
                        taps++
                        main.removeCallbacks(resetTaps)
                        if (taps >= 3) {
                            taps = 0
                            actions.visibility = View.VISIBLE
                        } else {
                            main.postDelayed(resetTaps, 650)
                        }
                    }
                }
            }
            true
        }
    }

    private fun requestClose() {
        controls?.visibility = View.GONE
        val callback = onCloseRequested
        if (callback != null) callback() else stopSelf()
    }

    private fun setFrame(frame: Bitmap?, mirrored: Boolean) {
        main.post {
            image?.scaleX = if (mirrored) -1f else 1f
            image?.setImageBitmap(frame)
            if (frame == null) image?.setBackgroundColor(Color.BLACK)
        }
    }

    private fun showOverlay() {
        main.post { root?.visibility = View.VISIBLE }
    }

    private fun hideOverlay() {
        main.post {
            controls?.visibility = View.GONE
            root?.visibility = View.GONE
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 2314
        private const val PREFS = "persistent_rectangle"

        @Volatile private var instance: PersistentLiveService? = null
        @Volatile var onCloseRequested: (() -> Unit)? = null

        fun lifecycleOwner(): LifecycleOwner? = instance
        fun updateFrame(frame: Bitmap?, mirrored: Boolean) {
            instance?.setFrame(frame, mirrored)
        }
        fun show() { instance?.showOverlay() }
        fun hide() { instance?.hideOverlay() }
    }
}

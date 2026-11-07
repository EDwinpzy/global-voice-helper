package com.dongpiandi.voicehelper

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.dongpiandi.voicehelper.assistant.AssistantAction

/**
 * Shows a single model-created reminder, speaks it, and asks whether to run its follow-up action.
 */
class ScheduledReminderActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val tts by lazy { VendorTtsClient(this) }
    private var reminder: ScheduledReminder? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val reminderId =
            intent.getStringExtra(ScheduledReminderScheduler.EXTRA_REMINDER_ID)
                ?.takeIf(String::isNotBlank)
        val store = ScheduledReminderStore(this)
        val loaded = reminderId?.let(store::find)
        if (loaded == null) {
            finish()
            return
        }
        reminder = loaded
        store.remove(loaded.id)
        if (loaded.autoRunFollowUp) {
            loaded.followUp?.let(::runFollowUp)
            finish()
            return
        }
        setContentView(buildContent(loaded))
        tts.speak("${loaded.title}。${loaded.message}")
        main.postDelayed(
            {
                dismissReminder()
            },
            AUTO_DISMISS_MS,
        )
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (isFinishing) tts.cancel()
        super.onDestroy()
    }

    @Deprecated("The reminder is optional and must not block the underlying app.")
    override fun onBackPressed() {
        dismissReminder()
    }

    private fun buildContent(reminder: ScheduledReminder): View {
        val root =
            FrameLayout(this).apply {
                setBackgroundColor(Color.argb(176, 0, 0, 0))
            }
        val card =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background =
                    GradientDrawable().apply {
                        cornerRadius = dp(28).toFloat()
                        setColor(Color.rgb(20, 17, 29))
                        setStroke(dp(2), Color.rgb(213, 107, 255))
                    }
                setPadding(dp(42), dp(34), dp(42), dp(32))
            }
        val cardParams =
            FrameLayout.LayoutParams(
                dp(780),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        root.addView(card, cardParams)

        card.addView(
            label(
                text = "定时提醒",
                size = 15f,
                color = ACCENT,
                bold = true,
            ),
        )
        card.addView(
            label(
                text = reminder.title,
                size = 32f,
                color = Color.WHITE,
                bold = true,
            ).apply {
                setPadding(0, dp(10), 0, 0)
            },
        )
        card.addView(
            label(
                text = reminder.message,
                size = 22f,
                color = Color.rgb(221, 217, 230),
                bold = false,
            ).apply {
                setPadding(0, dp(18), 0, 0)
                setLineSpacing(0f, 1.18f)
            },
        )

        val actions =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(28), 0, 0)
            }
        val primaryLabel =
            if (reminder.followUp == null) {
                "知道了"
            } else {
                reminder.confirmLabel?.takeIf(String::isNotBlank) ?: "现在查看"
            }
        val primary =
            actionButton(
                text = primaryLabel,
                primary = true,
            ) {
                confirmReminder()
            }
        actions.addView(
            primary,
            LinearLayout.LayoutParams(dp(250), dp(58)).apply {
                if (reminder.followUp != null) marginEnd = dp(18)
            },
        )
        if (reminder.followUp != null) {
            actions.addView(
                actionButton(
                    text = "稍后",
                    primary = false,
                ) {
                    dismissReminder()
                },
                LinearLayout.LayoutParams(dp(190), dp(58)),
            )
        }
        card.addView(actions)
        card.addView(
            label(
                text =
                    if (reminder.followUp == null) {
                        "按 OK 关闭"
                    } else {
                        "按 OK 确认，按返回稍后"
                    },
                size = 15f,
                color = Color.rgb(148, 139, 164),
                bold = false,
            ).apply {
                setPadding(0, dp(18), 0, 0)
            },
        )
        root.post { primary.requestFocus() }
        return root
    }

    private fun confirmReminder() {
        val current = reminder ?: return
        main.removeCallbacksAndMessages(null)
        tts.cancel()
        current.followUp?.let(::runFollowUp)
        finish()
    }

    private fun runFollowUp(followUp: AssistantAction.Immediate) {
        val launched =
            when (followUp) {
                is AssistantAction.OpenLiveFootball ->
                    LiveFootballLauncher.open(this, followUp.query)
                is AssistantAction.OpenVideoSearch ->
                    VideoSearchLauncher.open(
                        this,
                        followUp.query,
                        followUp.openFirstResult,
                    )
                is AssistantAction.OpenMusic ->
                    MusicPlayLauncher.open(this, followUp.query)
            }
        if (!launched) {
            val message =
                when (followUp) {
                    is AssistantAction.OpenLiveFootball ->
                        R.string.assistant_live_football_unavailable
                    is AssistantAction.OpenVideoSearch ->
                        R.string.assistant_video_pro_unavailable
                    is AssistantAction.OpenMusic ->
                        R.string.assistant_music_app_unavailable
                }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun dismissReminder() {
        main.removeCallbacksAndMessages(null)
        tts.cancel()
        finish()
    }

    private fun actionButton(
        text: String,
        primary: Boolean,
        action: () -> Unit,
    ): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 19f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            background = buttonBackground(primary)
            setOnClickListener { action() }
            isFocusable = true
            isFocusableInTouchMode = false
        }

    private fun buttonBackground(primary: Boolean): StateListDrawable {
        val idleColor =
            if (primary) Color.rgb(91, 53, 126) else Color.rgb(43, 37, 54)
        val idle =
            GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(idleColor)
                setStroke(
                    dp(1),
                    if (primary) Color.rgb(172, 101, 222) else Color.rgb(92, 82, 108),
                )
            }
        val focused =
            GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.rgb(213, 107, 255))
                setStroke(dp(2), Color.WHITE)
            }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), idle)
        }
    }

    private fun label(
        text: String,
        size: Float,
        color: Int,
        bold: Boolean,
    ): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(ColorStateList.valueOf(color))
            typeface =
                Typeface.create(
                    if (bold) "sans-serif-medium" else "sans-serif",
                    Typeface.NORMAL,
                )
            gravity = Gravity.CENTER_HORIZONTAL
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val ACCENT = 0xFFD56BFF.toInt()
        const val AUTO_DISMISS_MS = 60_000L
    }
}

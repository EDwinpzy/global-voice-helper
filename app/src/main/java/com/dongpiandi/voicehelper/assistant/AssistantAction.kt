package com.dongpiandi.voicehelper.assistant

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.json.JSONException
import org.json.JSONObject

/**
 * Device actions selected by the model.
 *
 * Natural-language intent is not decided by keyword lists. The model chooses one of these
 * capabilities and supplies normalized arguments. Keeping the executable set closed prevents a
 * model response from invoking arbitrary device behavior.
 */
sealed interface AssistantAction {
    sealed interface Immediate : AssistantAction

    data class OpenLiveFootball(
        val query: String?,
    ) : Immediate

    data class OpenVideoSearch(
        val query: String,
        val openFirstResult: Boolean,
    ) : Immediate

    data class OpenMusic(
        val query: String,
    ) : Immediate

    data class ScheduleLiveFootball(
        val query: String,
        val kickoffAtEpochMs: Long,
        val displayName: String,
    ) : AssistantAction

    data class ScheduleReminder(
        val triggerAtEpochMs: Long,
        val title: String,
        val message: String,
        val confirmLabel: String?,
        val followUp: Immediate?,
        val autoRunFollowUp: Boolean = false,
    ) : AssistantAction
}

internal data class AssistantActionEnvelope(
    val answer: String,
    val action: AssistantAction?,
    val actionRequested: Boolean,
)

internal object AssistantActionJson {
    fun parseEnvelope(
        raw: String,
        zone: ZoneId,
    ): AssistantActionEnvelope? {
        val jsonText = extractJsonObject(raw) ?: return null
        val root = JSONObject(jsonText)
        val answer = root.optString("answer").trim()
        if (answer.isEmpty()) {
            throw JSONException("assistant envelope has no answer")
        }

        val actionRequested = root.has("action") && !root.isNull("action")
        val action =
            if (actionRequested) {
                parseAction(root.optJSONObject("action"), zone)
            } else {
                null
            }
        return AssistantActionEnvelope(
            answer = answer,
            action = action,
            actionRequested = actionRequested,
        )
    }

    fun parseAction(
        action: JSONObject?,
        zone: ZoneId,
    ): AssistantAction? {
        if (action == null) return null
        return when (action.optString("type").trim().uppercase()) {
            "OPEN_LIVE_FOOTBALL" ->
                AssistantAction.OpenLiveFootball(
                    query = optionalText(action.opt("query"), MAX_QUERY_LENGTH),
                )
            "OPEN_VIDEO_SEARCH" -> {
                val query = requiredText(action.opt("query"), MAX_QUERY_LENGTH) ?: return null
                AssistantAction.OpenVideoSearch(
                    query = query,
                    openFirstResult = action.optBoolean("openFirstResult", false),
                )
            }
            "OPEN_MUSIC" -> {
                val query = requiredText(action.opt("query"), MAX_QUERY_LENGTH) ?: return null
                AssistantAction.OpenMusic(query)
            }
            "SCHEDULE_LIVE_FOOTBALL" -> {
                val query = requiredText(action.opt("query"), MAX_QUERY_LENGTH) ?: return null
                val kickoffAt =
                    parseEpochMillis(
                        action.opt("kickoffAt"),
                        zone = zone,
                        dateOnlyHour = null,
                    ) ?: return null
                AssistantAction.ScheduleLiveFootball(
                    query = query,
                    kickoffAtEpochMs = kickoffAt,
                    displayName =
                        optionalText(action.opt("displayName"), MAX_DISPLAY_NAME_LENGTH)
                            ?: query,
                )
            }
            "SCHEDULE_REMINDER" -> {
                val triggerAt =
                    parseEpochMillis(
                        action.opt("triggerAt"),
                        zone = zone,
                        dateOnlyHour = DEFAULT_DATE_ONLY_HOUR,
                    ) ?: return null
                val title = requiredText(action.opt("title"), MAX_TITLE_LENGTH) ?: return null
                val message = requiredText(action.opt("message"), MAX_MESSAGE_LENGTH) ?: return null
                val followUpRequested = action.has("followUp") && !action.isNull("followUp")
                val followUp =
                    if (followUpRequested) {
                        parseAction(action.optJSONObject("followUp"), zone) as? AssistantAction.Immediate
                            ?: return null
                    } else {
                        null
                    }
                val autoRunFollowUp = action.optBoolean("autoRunFollowUp", false)
                if (autoRunFollowUp && followUp == null) return null
                AssistantAction.ScheduleReminder(
                    triggerAtEpochMs = triggerAt,
                    title = title,
                    message = message,
                    confirmLabel =
                        optionalText(action.opt("confirmLabel"), MAX_CONFIRM_LABEL_LENGTH),
                    followUp = followUp,
                    autoRunFollowUp = autoRunFollowUp,
                )
            }
            else -> null
        }
    }

    fun encode(action: AssistantAction): JSONObject =
        when (action) {
            is AssistantAction.OpenLiveFootball ->
                JSONObject()
                    .put("type", "OPEN_LIVE_FOOTBALL")
                    .apply {
                        action.query?.let { put("query", it) }
                    }
            is AssistantAction.OpenVideoSearch ->
                JSONObject()
                    .put("type", "OPEN_VIDEO_SEARCH")
                    .put("query", action.query)
                    .put("openFirstResult", action.openFirstResult)
            is AssistantAction.OpenMusic ->
                JSONObject()
                    .put("type", "OPEN_MUSIC")
                    .put("query", action.query)
            is AssistantAction.ScheduleLiveFootball ->
                JSONObject()
                    .put("type", "SCHEDULE_LIVE_FOOTBALL")
                    .put("query", action.query)
                    .put("kickoffAt", Instant.ofEpochMilli(action.kickoffAtEpochMs).toString())
                    .put("displayName", action.displayName)
            is AssistantAction.ScheduleReminder ->
                JSONObject()
                    .put("type", "SCHEDULE_REMINDER")
                    .put("triggerAt", Instant.ofEpochMilli(action.triggerAtEpochMs).toString())
                    .put("title", action.title)
                    .put("message", action.message)
                    .put("autoRunFollowUp", action.autoRunFollowUp)
                    .apply {
                        action.confirmLabel?.let { put("confirmLabel", it) }
                        action.followUp?.let { put("followUp", encode(it)) }
                    }
        }

    private fun extractJsonObject(raw: String): String? {
        val text =
            raw
                .trim()
                .removePrefix("```json")
                .removePrefix("```JSON")
                .removePrefix("```")
                .trim()
        var start = text.indexOf('{')
        while (start >= 0) {
            var end = text.indexOf('}', start)
            while (end >= 0) {
                val candidate = text.substring(start, end + 1)
                if (isAnswerEnvelope(candidate)) return candidate
                end = text.indexOf('}', end + 1)
            }
            start = text.indexOf('{', start + 1)
        }
        return null
    }

    private fun isAnswerEnvelope(candidate: String): Boolean =
        runCatching {
            JSONObject(candidate).has("answer")
        }.getOrDefault(false)

    private fun requiredText(
        value: Any?,
        maxLength: Int,
    ): String? = optionalText(value, maxLength)

    private fun optionalText(
        value: Any?,
        maxLength: Int,
    ): String? {
        val text =
            when (value) {
                is String -> value
                is Number -> value.toString()
                else -> null
            }?.trim().orEmpty()
        return text.takeIf(String::isNotEmpty)?.take(maxLength)
    }

    private fun parseEpochMillis(
        value: Any?,
        zone: ZoneId,
        dateOnlyHour: Int?,
    ): Long? {
        if (value is Number) {
            return value.toLong().takeIf { it > 0L }
        }
        val text = (value as? String)?.trim().orEmpty()
        if (text.isEmpty()) return null
        text.toLongOrNull()?.let { return it.takeIf { epoch -> epoch > 0L } }

        val instant =
            runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
                ?: runCatching { ZonedDateTime.parse(text).toInstant() }.getOrNull()
                ?: runCatching { Instant.parse(text) }.getOrNull()
                ?: runCatching {
                    LocalDateTime.parse(text).atZone(zone).toInstant()
                }.getOrNull()
                ?: dateOnlyHour?.let { hour ->
                    runCatching {
                        LocalDate.parse(text).atTime(hour, 0).atZone(zone).toInstant()
                    }.getOrNull()
                }
        return instant?.toEpochMilli()
    }

    private const val MAX_QUERY_LENGTH = 80
    private const val MAX_DISPLAY_NAME_LENGTH = 100
    private const val MAX_TITLE_LENGTH = 100
    private const val MAX_MESSAGE_LENGTH = 240
    private const val MAX_CONFIRM_LABEL_LENGTH = 20
    private const val DEFAULT_DATE_ONLY_HOUR = 9
}

internal object AssistantActionValidator {
    fun validateAt(
        action: AssistantAction?,
        nowEpochMs: Long,
    ): AssistantAction? {
        if (action == null) return null
        return when (action) {
            is AssistantAction.OpenLiveFootball ->
                action.takeIf { it.query == null || it.query.isNotBlank() }
            is AssistantAction.OpenVideoSearch ->
                action.takeIf { it.query.isNotBlank() }
            is AssistantAction.OpenMusic ->
                action.takeIf { it.query.isNotBlank() }
            is AssistantAction.ScheduleLiveFootball ->
                action.takeIf {
                    it.query.isNotBlank() &&
                        it.kickoffAtEpochMs > nowEpochMs - SCHEDULE_TIME_TOLERANCE_MS
                }
            is AssistantAction.ScheduleReminder ->
                action.takeIf {
                    it.title.isNotBlank() &&
                        it.message.isNotBlank() &&
                        (!it.autoRunFollowUp || it.followUp != null) &&
                        it.triggerAtEpochMs > nowEpochMs - REMINDER_TIME_TOLERANCE_MS
                }
        }
    }

    private const val SCHEDULE_TIME_TOLERANCE_MS = 5L * 60L * 1_000L
    private const val REMINDER_TIME_TOLERANCE_MS = 60L * 1_000L
}

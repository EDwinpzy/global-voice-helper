package com.dongpiandi.voicehelper

import java.util.Locale

/** A deterministic command that can run without a model round-trip. */
internal sealed interface ExplicitCommand {
    data class ScheduleLiveFootball(
        val request: LiveFootballScheduleRequest,
    ) : ExplicitCommand

    data class PlayMusic(
        val query: String,
    ) : ExplicitCommand

    data class OpenLiveFootball(
        val request: LiveFootballRequest,
    ) : ExplicitCommand

    data class OpenVideoSearch(
        val request: VideoSearchRequest,
    ) : ExplicitCommand

    data class OpenApp(
        val request: AppLaunchRequest,
    ) : ExplicitCommand

    data class ControlDouyin(
        val request: DouyinVoiceRequest,
    ) : ExplicitCommand

    data class Control(
        val action: GlobalControlAction,
    ) : ExplicitCommand
}

/**
 * Keeps explicit remote-control commands working even when the model answers with plain text.
 *
 * Only commands with an unambiguous action verb and target are handled here. Questions,
 * troubleshooting requests and future timings still go through the model.
 */
internal object ExplicitCommandResolver {
    private val questionPrefixes =
        listOf(
            "怎么",
            "如何",
            "为什么",
            "为何",
            "怎样",
            "能不能",
            "可不可以",
            "可以怎么",
        )

    private val troubleshootingMarkers =
        listOf(
            "怎么办",
            "打不开",
            "播放不了",
            "没声音",
            "没有声音",
            "没反应",
            "无法",
            "失败",
            "不支持",
            "教程",
            "怎么设置",
            "如何设置",
        )

    fun resolve(transcript: String): ExplicitCommand? {
        val input = transcript.trim()
        if (input.isEmpty()) return null
        val normalized = input.lowercase(Locale.ROOT)
        if (questionPrefixes.any(normalized::startsWith)) return null
        if (troubleshootingMarkers.any(normalized::contains)) return null

        LiveFootballScheduleCommandParser.parse(input)?.let { request ->
            return ExplicitCommand.ScheduleLiveFootball(request)
        }
        DouyinVoiceCommandParser.parse(input)?.let { request ->
            return ExplicitCommand.ControlDouyin(request)
        }
        MusicPlayCommandParser.parse(
            input,
            allowImplicitMusic = MusicPlayCommandParser.looksLikeImplicitArtistTitle(input),
        )?.let { query ->
            return ExplicitCommand.PlayMusic(query)
        }
        LiveFootballCommandParser.parse(input)?.let { request ->
            return ExplicitCommand.OpenLiveFootball(request)
        }
        VideoSearchCommandParser.parse(input)?.let { request ->
            return ExplicitCommand.OpenVideoSearch(request)
        }
        GlobalControlCommandParser.parse(input)?.let { action ->
            return ExplicitCommand.Control(action)
        }
        AppLaunchCommandParser.parse(input)?.let { request ->
            return ExplicitCommand.OpenApp(request)
        }
        return null
    }
}

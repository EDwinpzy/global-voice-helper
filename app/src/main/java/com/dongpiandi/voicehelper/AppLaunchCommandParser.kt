package com.dongpiandi.voicehelper

internal data class AppLaunchRequest(
    val name: String,
)

/** Extracts a short "open/launch <app>" request without guessing at content commands. */
internal object AppLaunchCommandParser {
    private const val MIN_NAME_LENGTH = 2
    private const val MAX_NAME_LENGTH = 40

    private val commandPrefixes =
        listOf(
            "请帮我打开",
            "帮我打开一下",
            "帮我打开",
            "给我打开",
            "帮我启动",
            "启动一下",
            "打开一下",
            "给我启动",
            "打开",
            "启动",
            "运行",
            "进入",
        ).sortedByDescending(String::length)

    private val trailingDecoration =
        Regex("""(?:这个)?(?:APP|App|app|应用|软件)|[吧啊呀呗呢啦嘛]|谢谢|拜托|[\s，,。.!！?？:：]+$""")

    fun parse(transcript: String): AppLaunchRequest? {
        val input = transcript.trim().replace(Regex("\\s+"), " ")
        if (input.length < 3) return null

        val prefix = commandPrefixes.firstOrNull(input::startsWith) ?: return null
        var name = input.removePrefix(prefix).trim()
        while (true) {
            val stripped = trailingDecoration.replace(name, "").trim()
            if (stripped == name) break
            name = stripped
        }

        name = name.trim('“', '”', '"', '\'', '《', '》', '「', '」', '『', '』')
        if (name.length !in MIN_NAME_LENGTH..MAX_NAME_LENGTH) return null
        return AppLaunchRequest(name)
    }
}

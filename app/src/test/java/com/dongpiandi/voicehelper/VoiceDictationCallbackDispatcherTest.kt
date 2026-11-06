package com.dongpiandi.voicehelper

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceDictationCallbackDispatcherTest {
    @Test
    fun successfulSessionReportsOrderedLifecycleOnce() {
        val events = mutableListOf<String>()
        val dispatcher =
            VoiceDictationCallbackDispatcher(
                object : VoiceDictationCallbacks {
                    override fun onListeningStarted() {
                        events += "started"
                    }

                    override fun onPartial(text: String) {
                        events += "partial:$text"
                    }

                    override fun onFinished(text: String) {
                        events += "finished:$text"
                    }

                    override fun onDismissed() {
                        events += "dismissed"
                    }
                },
            )

        dispatcher.started()
        dispatcher.started()
        dispatcher.partial("今天")
        dispatcher.partial("今天天气")
        dispatcher.finished("今天天气")
        dispatcher.partial("迟到")
        dispatcher.dismissed()

        assertEquals(
            listOf("started", "partial:今天", "partial:今天天气", "finished:今天天气"),
            events,
        )
    }

    @Test
    fun cancelledSessionClosesOnce() {
        val events = mutableListOf<String>()
        val dispatcher =
            VoiceDictationCallbackDispatcher(
                object : VoiceDictationCallbacks {
                    override fun onListeningStarted() {
                        events += "started"
                    }

                    override fun onPartial(text: String) {
                        events += "partial:$text"
                    }

                    override fun onFinished(text: String) {
                        events += "finished:$text"
                    }

                    override fun onDismissed() {
                        events += "dismissed"
                    }
                },
            )

        dispatcher.partial("未开始")
        dispatcher.started()
        dispatcher.dismissed()
        dispatcher.dismissed()
        dispatcher.partial("取消后")
        dispatcher.finished("取消后")

        assertEquals(listOf("started", "dismissed"), events)
    }
}

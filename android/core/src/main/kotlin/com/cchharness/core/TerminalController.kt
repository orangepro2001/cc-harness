package com.cchharness.core

import com.cchharness.core.term.TerminalEmulator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException

sealed interface TermStatus {
    data object Connecting : TermStatus
    data object Live : TermStatus
    data class Reconnecting(val message: String) : TermStatus

    /** The shell inside the terminal ended (or it was closed elsewhere). */
    data object Ended : TermStatus
    data object Unauthorized : TermStatus

    /** Not retried: the server refused (terminal disabled, wrong network, unknown terminal). */
    data class Failed(val message: String) : TermStatus
}

/**
 * Keeps one terminal on screen: feeds the server's output into a [TerminalEmulator], sends input
 * in order (coalesced while a request is in flight) and reconnects when the link drops. The
 * server keeps the shell alive in tmux, so a reconnect repaints the same screen.
 */
class TerminalController(
    private val api: HarnessApi,
    val terminalId: String,
    private val scope: CoroutineScope,
    cols: Int = 80,
    rows: Int = 24,
    private val reconnectInitialMs: Long = 500,
    private val reconnectMaxMs: Long = 8_000,
) {
    val emulator = TerminalEmulator(cols, rows) { reply -> send(reply) }

    private val _status = MutableStateFlow<TermStatus>(TermStatus.Connecting)
    val status: StateFlow<TermStatus> = _status.asStateFlow()

    /** Bumped whenever the screen changed; renderers redraw on it. */
    private val _frame = MutableStateFlow(0L)
    val frame: StateFlow<Long> = _frame.asStateFlow()

    private val attachment = MutableStateFlow<String?>(null)
    private val input = Channel<ByteArray>(Channel.UNLIMITED)
    private var jobs: List<Job> = emptyList()

    init {
        emulator.onTitle = { _frame.value++ }
    }

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs = listOf(scope.launch { streamLoop() }, scope.launch { inputLoop() })
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        input.close()
    }

    fun send(text: String) = send(text.toByteArray(Charsets.UTF_8))

    fun send(bytes: ByteArray) {
        if (bytes.isNotEmpty()) input.trySend(bytes)
    }

    /** Resizes the local screen at once and tells the server. */
    fun resize(cols: Int, rows: Int) {
        if (cols == emulator.cols && rows == emulator.rows) return
        emulator.resize(cols, rows)
        _frame.value++
        val att = attachment.value ?: return // the next connection starts at this size
        scope.launch {
            try {
                api.terminalResize(att, cols, rows)
            } catch (e: IOException) {
            }
        }
    }

    private suspend fun streamLoop() {
        var backoff = reconnectInitialMs
        var first = true
        while (scope.isActive) {
            if (!first && _status.value !is TermStatus.Reconnecting) _status.value = TermStatus.Reconnecting("")
            try {
                api.terminalStream(terminalId, emulator.cols, emulator.rows).collect { ev ->
                    when (ev) {
                        is TermEvent.Attached -> {
                            // tmux repaints the whole screen on attach, so start from a clean emulator
                            emulator.reset()
                            attachment.value = ev.id
                            _status.value = TermStatus.Live
                            backoff = reconnectInitialMs
                            first = false
                            _frame.value++
                        }
                        is TermEvent.Output -> {
                            emulator.feed(ev.bytes)
                            _frame.value++
                        }
                        TermEvent.Exited -> {
                            _status.value = TermStatus.Ended
                            attachment.value = null
                            return@collect
                        }
                    }
                }
                if (_status.value == TermStatus.Ended) return
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthException) {
                _status.value = TermStatus.Unauthorized
                return
            } catch (e: ApiException) {
                if (e.code == 403 || e.code == 404 || e.code == 400) {
                    _status.value = if (e.code == 404) TermStatus.Ended else TermStatus.Failed(e.message.orEmpty())
                    return
                }
                _status.value = TermStatus.Reconnecting(e.message.orEmpty())
            } catch (e: IOException) {
                _status.value = TermStatus.Reconnecting(e.message.orEmpty())
            }
            attachment.value = null
            if (_status.value == TermStatus.Live) _status.value = TermStatus.Reconnecting("")
            first = false
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(reconnectMaxMs)
        }
    }

    private suspend fun inputLoop() {
        for (first in input) {
            // coalesce whatever is queued so a burst of keystrokes costs one request
            val buf = ByteArrayOutputStream().apply { write(first) }
            while (buf.size() < 16 * 1024) {
                val more = input.tryReceive().getOrNull() ?: break
                buf.write(more)
            }
            val bytes = buf.toByteArray()
            var attempts = 0
            while (scope.isActive && attempts < 3) {
                val att = attachment.filterNotNull().first() // waits across reconnects
                try {
                    api.terminalInput(att, bytes)
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    attempts++
                    delay(200)
                }
            }
        }
    }
}

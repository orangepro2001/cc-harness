package com.cchharness.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicBoolean

sealed interface Connection {
    data object Connecting : Connection
    data object Live : Connection
    data object Unauthorized : Connection
    data class Failed(val message: String) : Connection
}

/**
 * Follows one session: collects the event stream into [state] and exposes the
 * actions a chat screen needs. UI updates are coalesced (at most one snapshot
 * per [publishIntervalMs]) so replaying a long history stays cheap.
 */
class SessionController(
    private val api: HarnessApi,
    val sessionId: String,
    private val publishIntervalMs: Long = 50,
) {
    private val reducer = ChatReducer()
    private val dirty = AtomicBoolean(false)
    private val _state = MutableStateFlow(reducer.snapshot())
    private val _connection = MutableStateFlow<Connection>(Connection.Connecting)
    private var jobs: List<Job> = emptyList()

    val state: StateFlow<ChatState> = _state
    val connection: StateFlow<Connection> = _connection

    fun start(scope: CoroutineScope) {
        if (jobs.isNotEmpty()) return
        val collector = scope.launch {
            try {
                api.events(sessionId).collect { ev ->
                    reducer.apply(ev)
                    dirty.set(true)
                    _connection.value = Connection.Live
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthException) {
                _connection.value = Connection.Unauthorized
            } catch (e: Exception) {
                _connection.value = Connection.Failed(e.message ?: e.toString())
            } finally {
                publish()
            }
        }
        val publisher = scope.launch {
            while (isActive) {
                publish()
                delay(publishIntervalMs)
            }
        }
        jobs = listOf(collector, publisher)
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }

    private fun publish() {
        if (dirty.getAndSet(false)) _state.value = reducer.snapshot()
    }

    suspend fun send(text: String) = api.sendMessage(sessionId, text)

    suspend fun interrupt() = api.interrupt(sessionId)

    suspend fun stopProcess() = api.stop(sessionId)

    suspend fun setPermissionMode(mode: String) {
        api.updateSession(sessionId, permissionMode = mode)
    }

    suspend fun allow(requestId: String, remember: Boolean = false) =
        api.resolvePermission(sessionId, requestId, allow = true, remember = remember)

    suspend fun deny(requestId: String, message: String = "") =
        api.resolvePermission(sessionId, requestId, allow = false, message = message)

    /** Answers an AskUserQuestion request; [answers] maps question text to the chosen label(s). */
    suspend fun answer(item: PermissionItem, answers: Map<String, String>) {
        val input: JsonObject = buildAnswerInput(item.input, answers)
        api.resolvePermission(sessionId, item.requestId, allow = true, updatedInput = input)
    }
}

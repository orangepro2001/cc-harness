package com.cchharness.core

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChatReducerTest {
    private fun ev(seq: Long, json: String) =
        StreamEvent(seq, 0, HarnessJson.parseToJsonElement(json).jsonObject)

    /** Events exactly as a real `claude` session persisted them (recorded from the live server). */
    private fun realEvents(): List<StreamEvent> =
        javaClass.getResourceAsStream("/real-session.jsonl")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() }
            .map { SseParser.parseEvent(it)!! }

    @Test
    fun replaysARealSession() {
        val r = ChatReducer()
        realEvents().forEach(r::apply)
        val s = r.snapshot()

        assertEquals(29, s.lastSeq)
        assertEquals("manual", s.permissionMode)
        assertEquals("claude-haiku-5-5", s.model)

        val users = s.items.filterIsInstance<UserMessage>()
        assertEquals(3, users.size)
        assertTrue(users[0].text.startsWith("Use the Write tool"))

        val perm = s.items.filterIsInstance<PermissionItem>().single()
        assertEquals("Write", perm.toolName)
        assertEquals(PermState.AllowedRemembered, perm.state)
        assertTrue(perm.canRemember)
        assertFalse(perm.isQuestion)
        assertTrue(s.pendingPermissions.isEmpty())

        val write = s.items.filterIsInstance<ToolCall>().first { it.name == "Write" }
        assertTrue(write.summary.endsWith("hello.txt"), write.summary)
        assertTrue(write.result != null && !write.isError)

        val texts = s.items.filterIsInstance<AssistantText>().map { it.text }
        assertEquals("done", texts.first())
        assertTrue(texts.last().contains("hi-from-harness"), texts.last())

        val notes = s.items.filterIsInstance<Note>()
        assertTrue(notes.any { it.isError && it.text.contains("error_during_execution") }, "interrupted turn is reported")
        assertTrue(notes.any { it.text.contains("恢复上下文") }, "resume is announced")
        assertTrue(notes.any { it.text.contains("stopped by user") })
    }

    @Test
    fun replayIsIdempotentAndOrderIndependentOfDuplicates() {
        val once = ChatReducer().also { r -> realEvents().forEach(r::apply) }.snapshot()
        val twice = ChatReducer().also { r -> repeat(2) { realEvents().forEach(r::apply) } }.snapshot()
        assertEquals(once.items.size, twice.items.size)
        assertEquals(once.lastSeq, twice.lastSeq)
    }

    @Test
    fun streamingTextBecomesAnAssistantMessage() {
        val r = ChatReducer()
        r.apply(ev(0, """{"type":"stream_event","event":{"type":"message_start"}}"""))
        r.apply(ev(0, """{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"Hel"}}}"""))
        r.apply(ev(0, """{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"lo"}}}"""))
        assertEquals("Hello", r.snapshot().liveText)
        assertTrue(r.snapshot().items.isEmpty())

        r.apply(ev(5, """{"type":"assistant","message":{"content":[{"type":"text","text":"Hello"}]}}"""))
        val s = r.snapshot()
        assertEquals("", s.liveText)
        assertEquals("Hello", s.items.filterIsInstance<AssistantText>().single().text)
    }

    @Test
    fun liveEventsDoNotAffectSequenceTracking() {
        val r = ChatReducer()
        r.apply(ev(4, """{"type":"harness","kind":"user_message","text":"a"}"""))
        r.apply(ev(0, """{"type":"harness","kind":"status","status":"busy"}"""))
        r.apply(ev(5, """{"type":"harness","kind":"user_message","text":"b"}"""))
        r.apply(ev(0, """{"type":"harness","kind":"status","status":"idle"}"""))
        val s = r.snapshot()
        assertEquals(5, s.lastSeq)
        assertEquals("idle", s.status)
        assertEquals(2, s.items.size)
    }

    @Test
    fun permissionLifecycle() {
        val r = ChatReducer()
        r.apply(ev(1, """{"type":"control_request","request_id":"rq","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"ls"},"permission_suggestions":[]}}"""))
        var p = r.snapshot().pendingPermissions.single()
        assertEquals("rq", p.requestId)
        assertFalse(p.canRemember)
        r.apply(ev(2, """{"type":"harness","kind":"permission_resolved","requestId":"rq","allow":false}"""))
        assertEquals(PermState.Denied, r.snapshot().items.filterIsInstance<PermissionItem>().single().state)

        r.apply(ev(3, """{"type":"control_request","request_id":"rq2","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{},"permission_suggestions":[{"type":"setMode"}]}}"""))
        p = r.snapshot().pendingPermissions.single()
        assertTrue(p.canRemember)
        r.apply(ev(4, """{"type":"harness","kind":"permission_resolved","requestId":"rq2","cancelled":true}"""))
        assertEquals(PermState.Cancelled, r.snapshot().items.filterIsInstance<PermissionItem>().last().state)
        assertTrue(r.snapshot().pendingPermissions.isEmpty())
    }

    @Test
    fun askUserQuestionIsParsedAndAnswerInputBuilt() {
        val r = ChatReducer()
        r.apply(ev(1, """{"type":"control_request","request_id":"q1","request":{"subtype":"can_use_tool","tool_name":"AskUserQuestion","input":{"questions":[{"question":"Pick one","header":"H","multiSelect":false,"options":[{"label":"A","description":"first"},{"label":"B"}]}]}}}"""))
        val p = r.snapshot().pendingPermissions.single()
        assertTrue(p.isQuestion)
        val q = p.questions.single()
        assertEquals("Pick one", q.question)
        assertEquals(listOf("A", "B"), q.options.map { it.label })
        assertEquals("first", q.options[0].description)

        val input = buildAnswerInput(p.input, mapOf("Pick one" to "B"))
        assertTrue(input.containsKey("questions"), "original input is preserved")
        assertEquals("B", input["answers"]!!.jsonObject.str("Pick one"))
    }

    @Test
    fun toolResultsAreMatchedAndTruncated() {
        val r = ChatReducer()
        r.apply(ev(1, """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"seq 1 99999"}}]}}"""))
        val big = "x".repeat(9000)
        r.apply(ev(2, """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"$big","is_error":true}]}}"""))
        val t = r.snapshot().items.filterIsInstance<ToolCall>().single()
        assertTrue(t.isError)
        assertTrue(t.result!!.length < 8100 && t.result!!.contains("已截断"))
        // unknown ids are ignored instead of crashing
        r.apply(ev(3, """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"nope","content":"z"}]}}"""))
        assertEquals(1, r.snapshot().items.size)
    }

    @Test
    fun toolResultWithContentBlocks() {
        val r = ChatReducer()
        r.apply(ev(1, """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"/a"}}]}}"""))
        r.apply(ev(2, """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":[{"type":"text","text":"line1"},{"type":"image"}]}]}}"""))
        assertEquals("line1\n[image]", r.snapshot().items.filterIsInstance<ToolCall>().single().result)
    }

    @Test
    fun resultNotes() {
        val r = ChatReducer()
        r.apply(ev(1, """{"type":"result","subtype":"success","is_error":false,"duration_ms":2500,"total_cost_usd":0.0123}"""))
        val ok = r.snapshot().items.last()
        assertIs<Note>(ok)
        assertEquals("2.5s · \$0.0123", ok.text)
        assertFalse(ok.isError)
        r.apply(ev(2, """{"type":"result","subtype":"error_max_turns","is_error":true,"result":"too many"}"""))
        assertTrue((r.snapshot().items.last() as Note).isError)
    }

    @Test
    fun unknownEventsAreIgnored() {
        val r = ChatReducer()
        r.apply(ev(1, """{"type":"something_new","x":1}"""))
        r.apply(ev(2, """{"type":"system","subtype":"thinking_tokens"}"""))
        r.apply(ev(3, buildJsonObject { put("type", "harness") }.toString()))
        assertTrue(r.snapshot().items.isEmpty())
        assertEquals(3, r.snapshot().lastSeq)
    }

    @Test
    fun summaryPrefersCommonFields() {
        assertEquals("ls -la", summarizeInput(HarnessJson.parseToJsonElement("""{"command":"ls   -la","description":"x"}""").jsonObject))
        assertEquals("""{"a":1}""", summarizeInput(HarnessJson.parseToJsonElement("""{"a":1}""").jsonObject))
    }

    @Test
    fun attachmentsEffortAndCommandsAreReduced() {
        val r = ChatReducer()
        fun ev(seq: Long, json: String) = StreamEvent(seq, 0, HarnessJson.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject)
        r.apply(ev(1, """{"type":"system","subtype":"init","model":"m","slash_commands":["compact","review"]}"""))
        r.apply(ev(2, """{"type":"harness","kind":"user_message","text":"look","attachments":[{"id":"a1","name":"p.png","kind":"image","mime":"image/png","size":5},{"bad":true}]}"""))
        r.apply(ev(3, """{"type":"harness","kind":"effort_changed","effort":"high"}"""))
        val s = r.snapshot()
        assertEquals(listOf("compact", "review"), s.commands)
        assertEquals("high", s.effort)
        val u = s.items.filterIsInstance<UserMessage>().single()
        assertEquals(listOf("p.png"), u.attachments.map { it.name })
        assertTrue(u.attachments.single().isImage)
        // an init without a command list keeps the previous one
        r.apply(ev(4, """{"type":"system","subtype":"init","model":"m"}"""))
        assertEquals(listOf("compact", "review"), r.snapshot().commands)
    }
}

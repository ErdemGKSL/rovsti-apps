package dev.rovsti.core

import dev.rovsti.core.model.wa.AppId
import dev.rovsti.core.model.wa.SettingsApplyAnswer
import dev.rovsti.core.model.wa.SettingsApplyRequest
import dev.rovsti.core.model.wa.SettingsInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R9-3: every `wa.settings_apply` fixture decodes into the Kotlin models, the fixtures cover every screen of the
 * Rust machine, and a button's input takes the text of its field.
 */
class SettingsFixturesTest {
    private val dir = File(System.getProperty("rovsti.contract") ?: error("rovsti.contract not set"))
    private val json = Json { ignoreUnknownKeys = false }

    private fun answer(label: String): SettingsApplyAnswer {
        val stem = if (label.isEmpty()) "wa.settings_apply" else "wa.settings_apply.$label"
        val envelope = json.parseToJsonElement(File(dir, "$stem.answer.json").readText()).jsonObject
        return json.decodeFromJsonElement(SettingsApplyAnswer.serializer(), envelope.getValue("ok"))
    }

    @Test
    fun everyScreenHasAFixtureThatDecodes() {
        val byScreen =
            mapOf(
                "choose" to "",
                "enter_url" to "enter_url",
                "link_new" to "link_new_qr",
                "show_qr" to "show_qr",
                "show_code" to "show_code",
                "paste_key" to "paste_key",
                "connecting" to "connecting",
                "connected" to "connected",
                "share_key" to "share_key",
                "keys" to "keys_loaded",
                "confirm_revoke" to "confirm_revoke",
                "logged_out" to "logged_out",
                "confirm_delete" to "confirm_delete",
                "confirm_unlink" to "confirm_unlink",
                "failed" to "failed",
            )
        for ((kind, label) in byScreen) {
            val a = answer(label)
            assertEquals(label, kind, a.view.kind)
            assertTrue("$kind has a title", a.view.title.isNotEmpty())
        }
        assertEquals("link_new", answer("link_new_code").view.kind)
        assertEquals("failed", answer("bad_key").view.kind)
        assertEquals("failed", answer("bad_url").view.kind)
    }

    @Test
    fun theFirstPageHasTwoOptionsAndAGatewayRow() {
        val v = answer("").view
        assertEquals(listOf("edit_url", "link_new", "connect_existing"), v.buttons.map { it.id })
        assertEquals("Gateway", v.buttons.first().section)
        assertEquals(2, v.buttons.count { it.id != "edit_url" })
        assertEquals(null, v.back)
    }

    @Test
    fun qrAndCodePagesCarryTheirPayload() {
        assertEquals("2@qr-payload", answer("show_qr").view.qrPayload)
        assertEquals("ABCD-1234", answer("show_code").view.code)
        assertEquals("cancel", answer("show_code").view.back?.kind)
    }

    @Test
    fun keyRowsHaveLastUsedTextApplied() {
        val keys = answer("keys_loaded").view.keys
        assertEquals("this app · 2 min ago", keys[0].detail)
        assertEquals("never used", keys[1].detail)
        assertEquals("request_revoke", keys[1].revoke.kind)
    }

    @Test
    fun shareKeyIsSplitInTwoLines() {
        val v = answer("share_key").view
        assertEquals(2, v.keyLines.size)
        assertTrue(v.keyLines.all { it.length <= 24 })
    }

    @Test
    fun aLinkedStartVerifiesAndAFailedPageHasTheError() {
        assertEquals("verify", answer("linked_start").effects.single().kind)
        assertEquals("no route to host", answer("failed").view.error)
    }

    @Test
    fun aButtonInputTakesTheTextOfItsField() {
        val connect = answer("paste_key").view.buttons.single { it.field == "key" }
        assertEquals("key_pasted", connect.input?.kind)
        assertEquals(
            JsonPrimitive("rwk_x"),
            connect.input!!.withText("rwk_x").value,
        )
        val relink = answer("logged_out").view.buttons.single { it.id == "relink_code" }
        val filled = relink.input!!.withText("+905321112233").value as JsonObject
        assertEquals("phone_number", filled.getValue("type").jsonPrimitive.content)
        assertEquals("+905321112233", filled.getValue("number").jsonPrimitive.content)
        assertEquals(SettingsInput.unit("back"), SettingsInput.unit("back").withText("ignored"))
    }

    @Test
    fun theModelTravelsBackUnchanged() {
        val a = answer("connected")
        val request = SettingsApplyRequest(model = a.model, app = AppId.Messages, nowMs = 1, input = SettingsInput.back)
        val text = json.encodeToString(SettingsApplyRequest.serializer(), request)
        val back = json.decodeFromString(SettingsApplyRequest.serializer(), text)
        assertNotNull(back.model)
        assertEquals(a.model, back.model)
    }
}

package dev.rosty.messages.spam

import dev.rosty.core.model.messages.SpamRule
import dev.rosty.core.model.messages.SpamRules
import dev.rosty.core.model.phone.BlockedNumber
import dev.rosty.design.text.UiText
import dev.rosty.messages.R
import dev.rosty.messages.data.MessagesStore
import dev.rosty.platform.calllog.FakeBlocklistRepository
import dev.rosty.platform.sms.FakeSmsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/** E6.11: the Spam filter page's state and actions, on fakes. */
class SpamSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    @Before fun main() = Dispatchers.setMain(dispatcher)

    @After fun reset() = Dispatchers.resetMain()

    private class Rig(
        val model: SpamSettingsViewModel,
        val store: MessagesStore,
        val blocklist: FakeBlocklistRepository,
    )

    private fun TestScope.rig(allowed: Boolean = true): Rig {
        val core =
            SpamCore(
                prefsWith(
                    SpamRules(
                        rules =
                            listOf(SpamRule.UnknownWithLink, SpamRule.Sender("PROMO*"), SpamRule.ShortCodeOnly(false)),
                        allow = listOf("BANK"),
                    ),
                ),
            )
        val store = MessagesStore(core.ops, backgroundScope, io = dispatcher, debounceMs = 0)
        val blocklist =
            FakeBlocklistRepository(listOf(BlockedNumber(1, "+90 555 000 00 03", "+905550000003")), allowed = allowed)
        val dir = Files.createTempDirectory("spam-vm").toFile().apply { deleteOnExit() }
        val cleanup = CodeCleanup(dir.resolve(CodeCleanup.FILE), FakeSmsRepository(), { true }, {}, { 0L }, dispatcher)
        val actions = SpamActions(store, core.ops, blocklist)
        val model = SpamSettingsViewModel(SpamSources(actions, store.prefs, blocklist.numbers(), cleanup))
        return Rig(model, store, blocklist)
    }

    @Test fun thePageShowsTheRulesTheAllowListAndTheBlocklist() =
        scope.runTest {
            val r = rig()
            r.store.load()
            advanceUntilIdle()
            val ui = r.model.state.value
            assertTrue(ui.loaded && ui.enabled && ui.canBlock)
            assertEquals(
                listOf(
                    RuleRow(0, RuleKind.UnknownLinks, ""),
                    RuleRow(1, RuleKind.Sender, "PROMO*"),
                    RuleRow(2, RuleKind.ShortCodes, "", on = false),
                ),
                ui.rules,
            )
            assertEquals(listOf("BANK"), ui.allow)
            assertEquals(listOf("+90 555 000 00 03"), ui.blocked)
            assertFalse(ui.deleteCodes)
            assertFalse(
                rig(allowed = false)
                    .also { advanceUntilIdle() }
                    .model.state.value.canBlock,
            )
        }

    @Test fun savingARuleAnswersTheEditorAndAPill() =
        scope.runTest {
            val r = rig()
            advanceUntilIdle()
            var answer: RuleProblem? = RuleProblem.Failed
            r.model.onAction(SpamSettingsAction.SaveRule(null, RuleKind.Keyword, "kazandınız") { answer = it })
            advanceUntilIdle()
            assertNull(answer)
            assertEquals(
                RuleRow(3, RuleKind.Keyword, "kazandınız"),
                r.model.state.value.rules
                    .last(),
            )
            assertEquals(
                SpamSettingsEffect.Toast(UiText.Res(R.string.spam_rule_added)),
                r.model.effects.first(),
            )
            r.model.onAction(SpamSettingsAction.SaveRule(null, RuleKind.Sender, "promo*") { answer = it })
            advanceUntilIdle()
            assertEquals(RuleProblem.Duplicate, answer)
        }

    @Test fun deletingARuleOffersUndo() =
        scope.runTest {
            val r = rig()
            advanceUntilIdle()
            r.model.onAction(SpamSettingsAction.DeleteRule(1))
            advanceUntilIdle()
            assertEquals(2, r.model.state.value.rules.size)
            val undo = r.model.effects.first() as SpamSettingsEffect.Undo
            undo.undo()
            advanceUntilIdle()
            assertEquals(RuleRow(1, RuleKind.Sender, "PROMO*"), r.model.state.value.rules[1])
        }

    @Test fun allowingAndUnblocking() =
        scope.runTest {
            val r = rig()
            advanceUntilIdle()
            var added: Boolean? = null
            r.model.onAction(SpamSettingsAction.Allow("bank") { added = it })
            advanceUntilIdle()
            assertEquals(false, added)
            r.model.onAction(SpamSettingsAction.RemoveAllowed(0))
            r.model.onAction(SpamSettingsAction.Unblock("+90 555 000 00 03"))
            advanceUntilIdle()
            assertTrue(
                r.model.state.value.allow
                    .isEmpty(),
            )
            assertTrue(
                r.model.state.value.blocked
                    .isEmpty(),
            )
        }

    @Test fun theSwitchesChangeTheFilterAndM47() =
        scope.runTest {
            val r = rig()
            advanceUntilIdle()
            r.model.onAction(SpamSettingsAction.SetEnabled(false))
            r.model.onAction(SpamSettingsAction.SetDeleteCodes(true))
            advanceUntilIdle()
            assertFalse(r.model.state.value.enabled)
            assertTrue(r.model.state.value.deleteCodes)
        }
}

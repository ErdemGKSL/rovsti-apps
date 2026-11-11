package dev.rovsti.phone.incall

import dev.rovsti.core.model.phone.AudioState
import dev.rovsti.core.model.phone.CallSnapshot
import dev.rovsti.core.model.phone.SceneAnswer
import dev.rovsti.core.model.phone.SceneRequest
import dev.rovsti.core.model.phone.WireCallState
import dev.rovsti.core.ops.PhoneOps
import dev.rovsti.phone.favorites.FavPerson
import dev.rovsti.phone.favorites.FavoritesPhase
import dev.rovsti.phone.favorites.FavoritesUi
import kotlinx.collections.immutable.persistentListOf

internal const val SPLIT_CAP = 32L
internal const val DROP_CAP = 64L

/** The live call 2 (Mert) with call 3 (Ayşe) waiting on hold: the Two layout. */
internal fun twoCalls(
    canMerge: Boolean = true,
    canSwap: Boolean = true,
) = CallSnapshot(
    calls =
        listOf(
            liveCall(id = 2, name = "Mert Kaya", number = "+905559990000", contactId = 9, connectedAgoS = 272),
            liveCall(
                id = 3,
                state = WireCallState.Holding,
                name = "Ayşe Yılmaz",
                number = "+905558887766",
                contactId = 12,
                connectedAgoS = 600,
            ),
        ),
    foregroundCallId = 2,
    backgroundCallId = 3,
    keyguardLocked = false,
    canMerge = canMerge,
    canSwap = canSwap,
    canAddCall = true,
    audio = AudioState(),
)

/** A conference host 5 with children 6 (Mert) and 7 (Ayşe). */
internal fun conferenceCall(
    held: Boolean = false,
    caps: Long = SPLIT_CAP or DROP_CAP,
) = CallSnapshot(
    calls =
        buildList {
            add(
                liveCall(id = 5, name = null, number = "", contactId = null, connectedAgoS = 90)
                    .copy(children = listOf(6, 7)),
            )
            add(
                liveCall(id = 6, name = "Mert Kaya", number = "+905559990000", contactId = 9, connectedAgoS = 90)
                    .copy(parentId = 5, caps = caps),
            )
            add(
                liveCall(id = 7, name = "Ayşe Yılmaz", number = "+905558887766", contactId = 12, connectedAgoS = 90)
                    .copy(parentId = 5, caps = caps),
            )
            if (held) add(liveCall(id = 8, state = WireCallState.Holding, name = "Can Demir", contactId = 4))
        },
    foregroundCallId = 5,
    backgroundCallId = if (held) 8 else null,
    keyguardLocked = false,
    canMerge = false,
    canSwap = true,
    canAddCall = true,
    audio = AudioState(),
)

/** `scene.of` on the real Rust core through the bridge, so the tests prove the answer the screen really gets. */
internal class CoreSceneBackend(
    private val ops: PhoneOps,
) : FakeInCallBackend() {
    override suspend fun scene(snapshot: CallSnapshot): SceneAnswer? =
        (ops.sceneOf(SceneRequest(snapshot)) as? dev.rovsti.core.Outcome.Ok)?.value
}

internal val addCallPeople =
    FavoritesUi(
        phase = FavoritesPhase.Ready,
        favorites =
            persistentListOf(
                FavPerson("f1", 12, "Ayşe Yılmaz", "mobile", "+905558887766"),
                FavPerson("f2", 4, "Can Demir", "work", "+905553334455"),
            ),
        frequent = persistentListOf(FavPerson("q1", null, "+905550001122", null, "+905550001122")),
        pickables =
            persistentListOf(
                FavPerson("p1", 9, "Mert Kaya", "mobile", "+905559990000"),
                FavPerson("p2", 21, "Zeynep Arı", "home", "+905557776655"),
            ),
    )

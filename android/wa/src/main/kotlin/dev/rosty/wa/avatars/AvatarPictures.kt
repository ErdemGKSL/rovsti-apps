package dev.rosty.wa.avatars

import android.util.Base64
import dev.rosty.core.Outcome
import dev.rosty.core.model.wa.VaultPicturePutRequest
import dev.rosty.core.model.wa.VaultPicturesRequest
import dev.rosty.core.ops.VaultOps
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A stored profile picture: its WhatsApp picture id and the JPEG. */
class AvatarPicture(
    val id: String,
    val jpeg: ByteArray,
)

/**
 * Where [WaAvatars] keeps pictures instead of its disk folder. Since the vault (`plans/wa-vault.md` 13.5) Messages keeps
 * them in memory and in the DK-encrypted vault cache only ([VaultAvatarPictures]).
 */
interface AvatarPictures {
    suspend fun get(jid: String): AvatarPicture?

    suspend fun put(
        jid: String,
        picture: AvatarPicture,
    )

    /** The person has no picture any more. */
    suspend fun remove(jid: String)

    /** Forgets what is in memory (lock); the encrypted cache stays. */
    fun clear()
}

/**
 * [AvatarPictures] in memory, read once per unlock from the vault cache (`vault.pictures`) and written through to it
 * (`vault.picture_put`). While the vault is locked both calls fail and only memory is used, which [clear] empties.
 */
class VaultAvatarPictures(
    private val vault: VaultOps,
) : AvatarPictures {
    private val mutex = Mutex()
    private val held = java.util.concurrent.ConcurrentHashMap<String, AvatarPicture>()

    @Volatile private var loaded = false

    private suspend fun ensureLoaded() {
        if (loaded) return
        val answer = vault.pictures(VaultPicturesRequest()) as? Outcome.Ok ?: return
        loaded = true
        for (picture in answer.value.pictures) {
            val bytes = runCatching { Base64.decode(picture.jpeg, Base64.DEFAULT) }.getOrNull() ?: continue
            held.putIfAbsent(picture.jid, AvatarPicture(picture.pictureId, bytes))
        }
    }

    override suspend fun get(jid: String): AvatarPicture? =
        mutex.withLock {
            ensureLoaded()
            held[jid]
        }

    override suspend fun put(
        jid: String,
        picture: AvatarPicture,
    ) {
        mutex.withLock {
            held[jid] = picture
            val jpeg = Base64.encodeToString(picture.jpeg, Base64.NO_WRAP)
            vault.picturePut(VaultPicturePutRequest(jid, picture.id, jpeg))
        }
    }

    override suspend fun remove(jid: String) {
        mutex.withLock {
            held.remove(jid)
            vault.picturePut(VaultPicturePutRequest(jid))
        }
    }

    override fun clear() {
        // Not under the mutex: a lock must not wait for a write; a racing write lands in the next session's map.
        synchronized(held) {
            held.clear()
            loaded = false
        }
    }
}

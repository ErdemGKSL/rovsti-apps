package dev.rovsti.wa.avatars

import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import okio.Path.Companion.toOkioPath

/**
 * The model of a WhatsApp profile picture in an image request: `RowImage(model = WaAvatar(jid), ...)`. Coil keys it
 * `wa-avatar:<jid>` and the [WaAvatarFetcher] answers it from the avatar cache, so a row draws a picture as soon as
 * [WaAvatars] has stored one and shows its placeholder before that.
 */
data class WaAvatar(
    val jid: String,
) {
    /** The Coil cache key of this model. */
    val key: String get() = "$KEY_PREFIX$jid"

    companion object {
        const val KEY_PREFIX = "wa-avatar:"
    }
}

/** Reads the stored picture of [avatar] from the disk cache of [avatars]; Coil decodes it at the slot's size. */
class WaAvatarFetcher(
    private val avatars: WaAvatars,
    private val avatar: WaAvatar,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val file = avatars.file(avatar.jid) ?: return null
        return SourceFetchResult(
            source = ImageSource(file.toOkioPath(), options.fileSystem),
            mimeType = "image/jpeg",
            dataSource = DataSource.DISK,
        )
    }

    class Factory(
        private val avatars: WaAvatars,
    ) : Fetcher.Factory<WaAvatar> {
        override fun create(
            data: WaAvatar,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = WaAvatarFetcher(avatars, data, options)
    }
}

/** Registers the key and the fetcher of [WaAvatar] on an image loader's components. */
fun ComponentRegistry.Builder.waAvatars(avatars: WaAvatars): ComponentRegistry.Builder =
    add(Keyer<WaAvatar> { data, _ -> data.key }).add(WaAvatarFetcher.Factory(avatars))

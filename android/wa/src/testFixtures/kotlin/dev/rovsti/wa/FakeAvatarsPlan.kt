package dev.rovsti.wa

import dev.rovsti.core.model.wa.AvatarsPlanAnswer
import dev.rovsti.core.model.wa.AvatarsPlanRequest

/**
 * `wa.avatars_plan` as the fakes answer it: file names `<clean jid>~<clean id>.jpg`, the oldest files over the budget
 * listed in `remove`, and the id the first file of a jid holds. Same rules as `rovsti_app_core::wa_link::avatars`.
 */
object FakeAvatarsPlan {
    fun clean(part: String): String =
        part
            .map {
                if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '-' ||
                    it == '_'
                ) {
                    it
                } else {
                    '_'
                }
            }.joinToString("")

    fun fileName(
        jid: String,
        id: String,
    ) = "${clean(jid)}~${clean(id)}.jpg"

    fun idOfFile(
        jid: String,
        name: String,
    ): String? {
        val rest = name.removePrefix("${clean(jid)}~").takeIf { name.startsWith("${clean(jid)}~") } ?: return null
        return rest.removeSuffix(".jpg").takeIf { rest.endsWith(".jpg") && it.isNotEmpty() }
    }

    fun answer(req: AvatarsPlanRequest): AvatarsPlanAnswer {
        var total = req.files.sumOf { it.bytes }
        val remove = ArrayList<String>()
        for (file in req.files.sortedBy { it.modifiedMs }) {
            if (total <= req.maxBytes) break
            total -= file.bytes
            remove += file.path
        }
        val name = if (req.jid != null && req.pictureId != null) fileName(req.jid!!, req.pictureId!!) else null
        val held =
            req.jid?.let { jid -> req.files.firstNotNullOfOrNull { idOfFile(jid, it.path.substringAfterLast('/')) } }
        return AvatarsPlanAnswer(remove, name, held)
    }
}

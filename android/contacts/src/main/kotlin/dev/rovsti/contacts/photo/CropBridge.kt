package dev.rovsti.contacts.photo

import dev.rovsti.core.model.contacts.CropView
import dev.rovsti.design.media.crop.CropFrame

/** The crop page speaks the design system's [CropFrame]; the Rust core's request type is [CropView]. */
fun CropFrame.toView(): CropView = CropView(turns, zoom, cx, cy)

fun CropView.toFrame(): CropFrame = CropFrame(turns, zoom, cx, cy)

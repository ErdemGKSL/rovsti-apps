package dev.rosty.contacts.photo

import dev.rosty.core.model.contacts.CropView
import dev.rosty.design.media.crop.CropFrame

/** The crop page speaks the design system's [CropFrame]; the Rust core's request type is [CropView]. */
fun CropFrame.toView(): CropView = CropView(turns, zoom, cx, cy)

fun CropView.toFrame(): CropFrame = CropFrame(turns, zoom, cx, cy)

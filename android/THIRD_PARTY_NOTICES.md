# Third-party notices (Rovsti Android UI)

Code under `android/` and the Rovsti skill `.claude/skills/convx-motion-compose/` contains portions ported from the
following projects. Files with ported code carry a header naming the source.

## glisse

- Source: https://github.com/0kzh/glisse ("iOS 26 Liquid Glass for the web", TypeScript)
- Licence: MIT. Copyright (c) 2026 Kelvin Zhang (full text below).
- Used for: spring solver and constants, press/hold behaviour, refraction lens and rim-light maths, menu morph,
  tab bar / segmented control / switch / slider / input geometry and gesture rules, glass tokens.
- glisse credits, in its own comments, Aave's web liquid glass (lens shape) and kube.io's CSS/SVG liquid glass article
  (Snell's-law profile) as sources of its maths.

```
MIT License

Copyright (c) 2026 Kelvin Zhang

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Convx

- Source: https://github.com/cosmictaserdev-creator/Convx (Kotlin + Jetpack Compose music player, iOS-style Liquid Glass UI)
- Licence: GPL-3.0. Copyright: Convx Project (C) 2026, see the Convx git history for contributors. The licence text is the
  root `LICENSE` of this repository.
- Used for (planned paths under `android/design/src/main/kotlin/dev/rovsti/design/`): `backdrop/` (glass engine, GlassPool,
  BackdropFreeze, NavTransitionFreeze, BackdropStaticRegion), `shapes/`, `glass/` (GlassEffect, TopFadeBlur, tint tokens),
  `ios/` (foundation, menus, sheets, tab bar, navigation bar, list, swipe actions, button, toggle, slider, fields, toasts).
- Each ported file keeps its original header and adds
  `Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, <original path>`.
- Settings pages, ported into `android/shell/src/main/kotlin/dev/rovsti/shell/settings/` (E3.11): `ui/component/IosSettingsGroup.kt`
  (`IosSettingsGroup.kt`), `ui/screens/settings/SettingsPage.kt` (`SettingsPage.kt`) and the rows of
  `ui/screens/settings/AboutScreen.kt` (`AboutPage.kt`; the Convx links, donation rows and Discord are left out).
- Not ported (music-player specific): code Convx itself adapted from BitChord, Flamingo, MeloX-Android and Square
  (all GPL-3.0; see Convx `NOTICE`).

## Kyant0/backdrop v2.0.0

- Source: https://github.com/Kyant0/AndroidLiquidGlass (backdrop library), vendored and modified by Convx.
- Licence: Apache-2.0 (text below). Copyright 2025 Kyant0.
- Used for: Convx `ui/component/backdrop/**` ported to `design/backdrop/`.

## Kyant0/AndroidLiquidGlass (kmp branch)

- Source: https://github.com/Kyant0/AndroidLiquidGlass, branch `kmp`. Licence: Apache-2.0. Copyright 2025 Kyant0.
- Used for: liquid button, toggle, slider, bottom tabs and drag-gesture inspector (Convx `backdrop/catalog/**`).

## Kyant0/Capsule

- Source: https://github.com/Kyant0/Capsule. Licence: Apache-2.0. Copyright 2025 Kyant0.
- Used for: continuous rounded rectangle and capsule shapes (Convx `ui/component/shapes/**` to `design/shapes/`).

## compose-miuix-ui

- Licence: Apache-2.0. Copyright 2026, compose-miuix-ui contributors.
- Used for: `InteractiveHighlight` and `DampedDragAnimation` (Convx `backdrop/catalog/utils/`).

## compose-hig

- Source: https://github.com/ienground/compose-hig (a fork of Compose Cupertino, Scott Lanoue). Licence: Apache-2.0.
  Copyright (c) IENGROUND of IENLAB and prior contributors.
- Status: not used yet. Listed so that any file later ported from Convx `hig/` carries this attribution.

## compose-floating-tab-bar v1.0.1

- Source: https://github.com/elyesmansour/compose-floating-tab-bar (Elyes Mansour). Licence: Apache-2.0, http://www.apache.org/licenses/LICENSE-2.0
- Vendored by Convx and ported from Convx into `design/.../chrome/floatingtabbar/` (FloatingTabBar, FloatingTabBarBars,
  FloatingTabBarModel, FloatingTabBarMotion, FloatingTabBarTabs, AccessoryHost); the Convx modifications (Row/Column bars,
  draggable puck, folding) are GPL-3.0 like the rest of Convx.

## Haze

- Source: https://github.com/chrisbanes/haze. Licence: Apache-2.0. Copyright Chris Banes.
- Status: used. `:design` depends on `dev.chrisbanes.haze:haze` and `haze-materials` 1.7.3 for `glass/TopFadeBlur.kt` (ported from Convx, which pins 1.3.1).

## AndroidX WorkManager (work-runtime-ktx 2.11.0)

- Source: https://developer.android.com/jetpack/androidx/releases/work. Licence: Apache-2.0. Copyright The Android Open Source Project.
- Used for: the Contacts people-sync worker (X.1b). Pinned in `gradle/libs.versions.toml` (`work`); no module depends on it before X.1b.

## Apache License 2.0

The Apache-licensed files above keep their own copyright and licence notices in their headers.

```
                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS

   APPENDIX: How to apply the Apache License to your work.

      To apply the Apache License to your work, attach the following
      boilerplate notice, with the fields enclosed by brackets "[]"
      replaced with your own identifying information. (Don't include
      the brackets!)  The text should be enclosed in the appropriate
      comment syntax for the file format. We also recommend that a
      file or class name and description of purpose be included on the
      same "printed page" as the copyright notice for easier
      identification within third-party archives.

   Copyright 2020-2021 JetBrains s.r.o. and and respective authors and developers.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
```

## Coil

- Source: https://github.com/coil-kt/coil (`io.coil-kt.coil3:coil-core`, `coil-compose`, version 3.6.3)
- Licence: Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0). Used as a dependency only (image loading in
  `:design` `media/`); no Coil source is copied into this repository.

## Navigation 3

- Source: https://developer.android.com/guide/navigation/navigation-3 (`androidx.navigation3:navigation3-runtime`,
  `navigation3-ui`). Licence: Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0). Copyright The Android Open Source Project.
- Used as a dependency only (`design/transition/RovstiNavHost.kt`, `:catalog`); no Navigation 3 source is copied into this repository.

## Compose Reorderable

- Source: https://github.com/Calvin-LL/Reorderable (`sh.calvin.reorderable:reorderable`, version 3.0.0), the same
  release Convx pins (`gradle/libs.versions.toml:28,80`). Copyright 2023 Calvin Liang.
- Licence: Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0). Used as a dependency only (the drag-to-reorder of
  the Favorites tab, `contacts/favorites/FavoritesReorder.kt`); no Reorderable source is copied into this repository.

## Takumi (takumi 2.18.2, takumi-core 0.34.0, takumi-raster 0.5.11, takumi-svg)

- Source: https://github.com/kane50613/takumi (crates.io `takumi`, pinned `=2.18.2`). Copyright (c) 2025 Kane Wang.
- Licence: MIT OR Apache-2.0; Rovsti uses it under the MIT terms below (the Apache-2.0 text further up in this file is
  the alternative). Used as a dependency only by `crates/rovsti_app_core/src/cards/` (share cards); no Takumi source is
  copied into this repository.
- Takumi carries third-party notices for code it ports (Chromium Blink and Skia, emoji-segmenter, ICU); they ship with
  the library and are reproduced after the MIT text.

### Takumi MIT licence

```text
MIT License

Copyright (c) 2025 Kane Wang

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### Takumi third-party notice: Chromium (Blink, Skia), from takumi-core LICENSE-CHROMIUM

```text
Parts of takumi-core follow algorithms from Chromium's Blink renderer and the
Skia library it draws with. Each part names its source below, followed by the
notice that source carries.

1. The dash and dot spacing in src/layout/border.rs (`BorderStyle::dash_pattern`,
   `square_dash`, and `select_best_dash_gap`), from
   third_party/blink/renderer/platform/graphics/styled_stroke_data.cc, and
   `FixedPoint`, `LayoutUnit`, `TextRunLayoutUnit`, and `InlineLayoutUnit` in
   src/layout_unit.rs, from
   third_party/blink/renderer/platform/geometry/layout_unit.h, and
   `FontFace::get_font_selection_capabilities` in src/fonts/font_face.rs, from
   third_party/blink/renderer/core/css/font_face.cc, and
   `FontCustomPlatformData` and `FontPlatformData` in
   src/fonts/font_custom_platform_data.rs, from
   third_party/blink/renderer/platform/fonts/font_custom_platform_data.cc and
   third_party/blink/renderer/platform/fonts/font_platform_data.h.

Copyright (c) 2012, Google Inc. All rights reserved.
Copyright (C) 2013 Google Inc. All rights reserved.
Copyright (C) 2007 Apple Computer, Inc.
Copyright (c) 2006, 2007, 2008, 2009, Google Inc. All rights reserved.
Copyright (C) 2010 Company 100, Inc.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

   * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
   * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
   * Neither the name of Google Inc. nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.


2. `Color::dark` and `Color::light` in src/style/properties/color.rs, from
   third_party/blink/renderer/platform/graphics/color.cc, and
   `FontDataForRangeSet` in src/fonts/font_data_for_range_set.rs, from
   third_party/blink/renderer/platform/fonts/font_data_for_range_set.h, and
   `FontData::font_data_for_character` in src/fonts/font_fallback_list.rs, from
   third_party/blink/renderer/platform/fonts/segmented_font_data.cc, and
   `SimpleFontData::small_caps_font_data` in src/fonts/simple_font_data.rs, from
   third_party/blink/renderer/platform/fonts/simple_font_data.cc, and
   `FontFaceCache` in src/fonts/font_face_cache.rs, `CssSegmentedFontFace` in
   src/fonts/css_segmented_font_face.rs, `FontFace::get_font_data` in
   src/fonts/font_face.rs, and `CssFontSelector::get_font_data` in
   src/fonts/css_font_selector.rs, from
   third_party/blink/renderer/core/css/font_face_cache.cc,
   third_party/blink/renderer/core/css/css_segmented_font_face.cc,
   third_party/blink/renderer/core/css/css_font_face.cc, and
   third_party/blink/renderer/core/css/css_font_selector.cc.

Copyright (C) 2003, 2004, 2005, 2006, 2008, 2009 Apple Inc. All rights reserved.
Copyright (C) 2007, 2008, 2011 Apple Inc. All rights reserved.
          (C) 2007, 2008 Nikolas Zimmermann <zimmermann@kde.org>
Copyright (C) 2013 Google Inc. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:
1. Redistributions of source code must retain the above copyright
   notice, this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright
   notice, this list of conditions and the following disclaimer in the
   documentation and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY APPLE COMPUTER, INC. ``AS IS'' AND ANY
EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL APPLE COMPUTER, INC. OR
CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.


3. `Color::inset_outset` in src/style/properties/color.rs, `BorderShape`,
   `Miter`, `StyledLine` and the side order in src/painter/border.rs,
   `BoxSideRect` in src/painter/box_side.rs, `OutlineIsland::paint`
   and `ComplexOutline` in src/painter/outline.rs, `RightAngleContour` in
   src/layout/inline/outline.rs, the decoration lines in
   src/painter/decoration.rs, the property rules in src/paint_property.rs,
   `ConversionContext` in src/paint_chunk.rs, the corners and side clips
   in src/layout/contoured_rect.rs, the table layout in src/layout/table/,
   the table painting in src/painter/table.rs, the background layer geometry in
   src/layout/background_image_geometry.rs, and the line box alignment in
   src/layout/inline/line_box.rs, from
   third_party/blink/renderer/core/paint/box_border_painter.cc,
   third_party/blink/renderer/core/paint/outline_painter.cc,
   third_party/blink/renderer/core/paint/decoration_line_painter.cc,
   third_party/blink/renderer/core/paint/text_decoration_info.cc,
   third_party/blink/renderer/core/paint/text_painter.cc,
   third_party/blink/renderer/core/paint/paint_property_tree_builder.cc,
   third_party/blink/renderer/core/paint/contoured_border_geometry.cc,
   third_party/blink/renderer/platform/geometry/contoured_rect.cc,
   third_party/blink/renderer/core/paint/background_image_geometry.cc,
   third_party/blink/renderer/core/paint/box_painter_base.cc,
   third_party/blink/renderer/core/paint/table_painters.cc,
   third_party/blink/renderer/core/layout/table/table_layout_algorithm.cc,
   third_party/blink/renderer/core/layout/table/table_section_layout_algorithm.cc,
   third_party/blink/renderer/core/layout/table/table_row_layout_algorithm.cc,
   third_party/blink/renderer/core/layout/table/table_layout_utils.cc,
   third_party/blink/renderer/core/layout/table/table_layout_algorithm_types.cc,
   third_party/blink/renderer/core/layout/table/table_borders.cc,
   third_party/blink/renderer/core/layout/table/table_node.cc,
   third_party/blink/renderer/core/layout/table/layout_table_column_visitor.h,
   third_party/blink/renderer/core/layout/table/table_fragment_data.h,
   third_party/blink/renderer/core/layout/inline/inline_box_state.cc,
   third_party/blink/renderer/platform/geometry/path_builder.cc,
   third_party/blink/renderer/platform/graphics/compositing/paint_chunks_to_cc_layer.cc;
   and `HarfBuzzRunGlyphData` in src/fonts/shaping/glyph_data.rs and
   `GlyphDataRange` in src/fonts/shaping/glyph_data_range.rs, from
   third_party/blink/renderer/platform/fonts/shaping/glyph_data.h and
   third_party/blink/renderer/platform/fonts/shaping/glyph_data_range.cc;
   and `ScriptRunIterator` in src/fonts/script_run_iterator.rs,
   `SymbolsIterator` in src/fonts/symbols_iterator.rs, `FontFallbackPriority`
   in src/fonts/font_fallback_priority.rs, `RunSegmenter` in
   src/fonts/shaping/run_segmenter.rs, and `EmojiSegmentationCategory` in
   src/text/emoji_segmentation_category.rs, from
   third_party/blink/renderer/platform/fonts/script_run_iterator.cc,
   third_party/blink/renderer/platform/fonts/symbols_iterator.cc,
   third_party/blink/renderer/platform/fonts/font_fallback_priority.h,
   third_party/blink/renderer/platform/fonts/shaping/run_segmenter.cc, and
   third_party/blink/renderer/platform/text/emoji_segmentation_category_inline_header.h;
   and `FontFallbackIterator` in src/fonts/font_fallback_iterator.rs and
   `ShapeResultView` in src/fonts/shaping/shape_result_view.rs, from
   third_party/blink/renderer/platform/fonts/font_fallback_iterator.cc and
   third_party/blink/renderer/platform/fonts/shaping/shape_result_view.cc;
   and `ShapeResultSpacing` in src/fonts/shaping/shape_result_spacing.rs and
   `JustificationContext` in src/text/justification_opportunity.rs, from
   third_party/blink/renderer/platform/fonts/shaping/shape_result_spacing.cc and
   third_party/blink/renderer/platform/text/justification_opportunity.cc;
   and `ShapingLineBreaker` in src/fonts/shaping/shaping_line_breaker.rs, from
   third_party/blink/renderer/platform/fonts/shaping/shaping_line_breaker.cc;
   and `InlineItem` in src/layout/inline/inline_item.rs, `InlineItemsBuilder`
   in src/layout/inline/inline_items_builder.rs, `collect_inlines` in
   src/layout/inline/inline_node.rs, `InlineItemSegments` in
   src/layout/inline/inline_item_segment.rs, `InlineNodeData` in
   src/layout/inline/inline_node_data.rs, `BidiParagraph` in
   src/text/bidi_paragraph.rs, the marker margins and `width_of_symbol` in
   src/layout/list_marker.rs, `LineBreaker` in src/layout/inline/line_breaker.rs,
   `LineInfo` in src/layout/inline/line_info.rs, `InlineItemResult` and
   `HyphenResult` in src/layout/inline/inline_item_result.rs, `InlineBreakToken`
   in src/layout/inline/inline_break_token.rs, `LineBreakPoint` in
   src/layout/inline/line_break_point.rs, `InlineItemTextIndex` in
   src/layout/inline/inline_item_text_index.rs, the exclusions in
   src/layout/exclusions/, `BfcOffset` and `BfcRect` in src/layout/bfc_rect.rs,
   the float placement in src/layout/floats_utils.rs, `InlineBoxState` and
   `InlineLayoutStateStack` in src/layout/inline/inline_box_state.rs,
   `LogicalLineBuilder` in src/layout/inline/logical_line_builder.rs,
   `LogicalLineItem` in src/layout/inline/logical_line_item.rs, `FontHeight` in
   src/fonts/font_height.rs, `compute_relative_offset_for_inline` in
   src/layout/relative_utils.rs, the logical geometry in
   src/layout/logical_rect.rs, `LayoutResult::baseline_metrics` in
   src/layout/inline/inline_item_result.rs, `InlineLayoutAlgorithm` and
   `TextAlign::line_offset_for_text_align` in
   src/layout/inline/inline_layout_algorithm.rs, the justification in
   src/layout/inline/justification_utils.rs, `LeadingFloats` in
   src/layout/inline/leading_floats.rs, `InlineChildLayoutContext` in
   src/layout/inline/inline_child_layout_context.rs, `LineTruncator` in
   src/layout/inline/line_truncator.rs, `MinMaxSizes` and
   `InlineNode::compute_min_max_sizes` in src/layout/inline/min_max_sizes.rs,
   `ScoreLineBreaker` and `ScoreLineBreakContext` in
   src/layout/inline/score_line_breaker.rs, the bisection in
   src/layout/inline/paragraph_line_breaker.rs, `LineBreakCandidate` in
   src/layout/inline/line_break_candidate.rs, `LineWidths` in
   src/layout/inline/line_widths.rs, the line clamp in
   src/layout/constraint_space.rs, `capitalize` in src/text/capitalize.rs,
   `CaseMap` in src/text/case_map.rs, `SmallCapsIterator` in
   src/fonts/small_caps_iterator.rs, `OpenTypeCapsSupport` in
   src/fonts/open_type_caps_support.rs, `CaseMapIntend::fill_buffer` in
   src/fonts/shaping/case_mapping_harfbuzz_buffer_filler.rs, `LineFitter` and the
   `text-fit` scales in src/layout/inline/text_fit_utils.rs and
   src/layout/inline/text_fit_scale.rs, and the `white-space` predicates in
   src/style/properties/mod.rs and src/style/properties/text_wrap.rs, from
   third_party/blink/renderer/core/layout/inline/inline_item.cc,
   third_party/blink/renderer/core/layout/inline/inline_items_builder.cc,
   third_party/blink/renderer/core/layout/inline/inline_node.cc,
   third_party/blink/renderer/core/layout/inline/inline_item_segment.cc,
   third_party/blink/renderer/core/layout/inline/inline_node_data.h,
   third_party/blink/renderer/platform/text/bidi_paragraph.cc,
   third_party/blink/renderer/core/layout/list/list_marker.cc,
   third_party/blink/renderer/core/layout/inline/line_breaker.cc,
   third_party/blink/renderer/core/layout/inline/line_info.cc,
   third_party/blink/renderer/core/layout/inline/inline_item_result.cc,
   third_party/blink/renderer/core/layout/inline/hyphen_result.cc,
   third_party/blink/renderer/core/layout/inline/inline_break_token.h,
   third_party/blink/renderer/core/layout/inline/line_break_point.h,
   third_party/blink/renderer/core/layout/inline/inline_item_text_index.h,
   third_party/blink/renderer/core/layout/exclusions/exclusion_space.cc,
   third_party/blink/renderer/core/layout/exclusions/exclusion_area.h,
   third_party/blink/renderer/core/layout/exclusions/layout_opportunity.cc,
   third_party/blink/renderer/core/layout/exclusions/line_layout_opportunity.h,
   third_party/blink/renderer/core/layout/geometry/bfc_rect.h,
   third_party/blink/renderer/core/layout/floats_utils.cc,
   third_party/blink/renderer/core/layout/unpositioned_float.h,
   third_party/blink/renderer/core/layout/positioned_float.h,
   third_party/blink/renderer/core/layout/inline/logical_line_builder.cc,
   third_party/blink/renderer/core/layout/inline/logical_line_item.cc,
   third_party/blink/renderer/core/layout/inline/inline_items_data.cc,
   third_party/blink/renderer/platform/fonts/font_height.h,
   third_party/blink/renderer/core/layout/relative_utils.cc,
   third_party/blink/renderer/core/layout/geometry/logical_rect.h,
   third_party/blink/renderer/core/layout/logical_box_fragment.cc,
   third_party/blink/renderer/core/layout/inline/inline_layout_algorithm.cc,
   third_party/blink/renderer/core/layout/inline/justification_utils.cc,
   third_party/blink/renderer/core/layout/inline/leading_floats.h,
   third_party/blink/renderer/core/layout/inline/inline_child_layout_context.cc,
   third_party/blink/renderer/core/layout/length_utils.cc,
   third_party/blink/renderer/core/layout/inline/line_truncator.cc,
   third_party/blink/renderer/core/layout/min_max_sizes.h,
   third_party/blink/renderer/core/layout/inline/score_line_breaker.cc,
   third_party/blink/renderer/core/layout/inline/score_line_break_context.h,
   third_party/blink/renderer/core/layout/inline/paragraph_line_breaker.cc,
   third_party/blink/renderer/core/layout/inline/line_break_candidate.cc,
   third_party/blink/renderer/core/layout/inline/line_widths.cc,
   third_party/blink/renderer/core/layout/line_clamp_data.h,
   third_party/blink/renderer/platform/text/capitalize.cc,
   third_party/blink/renderer/platform/wtf/text/case_map.cc,
   third_party/blink/renderer/platform/fonts/small_caps_iterator.cc,
   third_party/blink/renderer/platform/fonts/opentype/open_type_caps_support.cc,
   third_party/blink/renderer/platform/fonts/shaping/case_mapping_harfbuzz_buffer_filler.cc,
   third_party/blink/renderer/core/layout/inline/text_fit_utils.cc,
   third_party/blink/renderer/core/layout/inline/text_fit_scale.h, and
   third_party/blink/renderer/core/css/white_space.h;
   and `VariableFontSubType::probe_variable_font` in
   src/fonts/font_custom_platform_data.rs and the tests in
   src/fonts/font_selection_types.rs and src/fonts/font_face_cache.rs, from
   third_party/blink/renderer/platform/fonts/opentype/font_format_check.cc,
   third_party/blink/renderer/platform/fonts/font_selection_types_test.cc, and
   third_party/blink/renderer/core/css/font_face_cache_test.cc.

Copyright 2014, 2015, 2016, 2017, 2018, 2019, 2020, 2023, 2024, 2025 The Chromium Authors

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

   * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
   * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
   * Neither the name of Google LLC nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.


4. `skips_ink` and its character tables in src/layout/intercept.rs, and the
   predicates in src/text/character.rs, from
   third_party/blink/renderer/platform/text/character.h,
   third_party/blink/renderer/platform/text/character.cc,
   third_party/blink/renderer/platform/text/character_emoji.cc, and
   third_party/blink/renderer/platform/text/character_property_data.h.

Copyright (C) 2014 Google Inc. All rights reserved.
Copyright 2015, 2016 The Chromium Authors

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

   * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
   * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
   * Neither the name of Google LLC nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

5. The Gaussian blur passes in src/blur.rs, from Skia's src/core/SkBlurEngine.cpp.

Copyright (c) 2011 Google Inc. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

  * Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.

  * Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in
    the documentation and/or other materials provided with the
    distribution.

  * Neither the name of the copyright holder nor the names of its
    contributors may be used to endorse or promote products derived
    from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.


6. `ShapeResult` in src/fonts/shaping/shape_result.rs, `ShapeResultRun` in
   src/fonts/shaping/shape_result_run.rs, and `HarfBuzzShaper` in
   src/fonts/shaping/harfbuzz_shaper.rs, from
   third_party/blink/renderer/platform/fonts/shaping/shape_result.cc,
   third_party/blink/renderer/platform/fonts/shaping/shape_result.h,
   third_party/blink/renderer/platform/fonts/shaping/shape_result_run.h, and
   third_party/blink/renderer/platform/fonts/shaping/harfbuzz_shaper.cc.

Copyright (c) 2012 Google Inc. All rights reserved.
Copyright (C) 2013 BlackBerry Limited. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

   * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
   * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
   * Neither the name of Google Inc. nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.


7. `FontFallbackList` in src/fonts/font_fallback_list.rs, from
   third_party/blink/renderer/platform/fonts/font_fallback_list.cc, and
   `FontSelectionDescription::merge_font_variation_settings_with_descriptor` in
   src/fonts/font_description.rs, from
   third_party/blink/renderer/platform/fonts/font_description.cc.

Copyright (C) 2006 Apple Computer, Inc.  All rights reserved.
Copyright (C) 2007 Nicholas Shanks <contact@nickshanks.com>
Copyright (C) 2008 Apple Inc. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

1.  Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.
2.  Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in the
    documentation and/or other materials provided with the distribution.
3.  Neither the name of Apple Computer, Inc. ("Apple") nor the names of
    its contributors may be used to endorse or promote products derived
    from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY APPLE AND ITS CONTRIBUTORS "AS IS" AND ANY
EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL APPLE OR ITS CONTRIBUTORS BE LIABLE FOR ANY
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.


8. `FontSelectionValue`, `FontSelectionRange`, `FontSelectionRequest`, and
   `FontSelectionCapabilities` in src/fonts/font_selection_types.rs, and
   `FontSelectionAlgorithm` in src/fonts/font_selection_algorithm.rs, from
   third_party/blink/renderer/platform/fonts/font_selection_types.h,
   third_party/blink/renderer/platform/fonts/font_selection_types.cc, and
   third_party/blink/renderer/platform/fonts/font_selection_algorithm.cc.

Copyright (C) 2017 Apple Inc. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:
1. Redistributions of source code must retain the above copyright
   notice, this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright
   notice, this list of conditions and the following disclaimer in the
   documentation and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY APPLE INC. AND ITS CONTRIBUTORS ``AS IS''
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL APPLE INC. OR ITS CONTRIBUTORS
BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF
THE POSSIBILITY OF SUCH DAMAGE.


9. The custom-property variable substitution in src/style/stylesheets_vars.rs,
   the computed custom-property inheritance in src/style/custom_properties.rs,
   and declaration staging in src/style/stylesheets.rs, adapted from
   third_party/blink/renderer/core/css/resolver/style_cascade.cc,
   third_party/blink/renderer/core/css/resolver/cascade_resolver.cc, and
   third_party/blink/renderer/core/css/parser/css_variable_parser.cc.
   src/style/stylesheets_tests.rs includes differential cases based on
   third_party/blink/renderer/core/css/resolver/style_cascade_test.cc.

Copyright 2015-2020 The Chromium Authors.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

   * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
   * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
   * Neither the name of Google LLC nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### Takumi third-party notice: emoji-segmenter, from takumi-core LICENSE-EMOJI-SEGMENTER

```text
takumi-core/src/fonts/emoji_presentation_scanner.rs follows the emoji presentation
grammar in emoji_presentation_scanner.rl from emoji-segmenter
(https://github.com/google/emoji-segmenter), at revision
955936be8b391e00835257059607d7c5b72ce744. It matches the grammar by hand
instead of compiling it with Ragel.

Copyright 2019 Google LLC


                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS

   APPENDIX: How to apply the Apache License to your work.

      To apply the Apache License to your work, attach the following
      boilerplate notice, with the fields enclosed by brackets "[]"
      replaced with your own identifying information. (Don't include
      the brackets!)  The text should be enclosed in the appropriate
      comment syntax for the file format. We also recommend that a
      file or class name and description of purpose be included on the
      same "printed page" as the copyright notice for easier
      identification within third-party archives.

   Copyright [yyyy] [name of copyright owner]

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
```

### Takumi third-party notice: ICU (Unicode License v3), from takumi-core LICENSE-ICU

```text
`adjust_ws_levels` in src/text/bidi_paragraph.rs follows `adjustWSLevels` in
ICU's icu4c/source/common/ubidi.cpp, which carries this notice.

UNICODE LICENSE V3

COPYRIGHT AND PERMISSION NOTICE

Copyright © 1991-2026 Unicode, Inc.

NOTICE TO USER: Carefully read the following legal agreement. BY
DOWNLOADING, INSTALLING, COPYING OR OTHERWISE USING DATA FILES, AND/OR
SOFTWARE, YOU UNEQUIVOCALLY ACCEPT, AND AGREE TO BE BOUND BY, ALL OF THE
TERMS AND CONDITIONS OF THIS AGREEMENT. IF YOU DO NOT AGREE, DO NOT
DOWNLOAD, INSTALL, COPY, DISTRIBUTE OR USE THE DATA FILES OR SOFTWARE.

Permission is hereby granted, free of charge, to any person obtaining a
copy of data files and any associated documentation (the "Data Files") or
software and any associated documentation (the "Software") to deal in the
Data Files or Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, and/or sell
copies of the Data Files or Software, and to permit persons to whom the
Data Files or Software are furnished to do so, provided that either (a)
this copyright and permission notice appear with all copies of the Data
Files or Software, or (b) this copyright and permission notice appear in
associated Documentation.

THE DATA FILES AND SOFTWARE ARE PROVIDED "AS IS", WITHOUT WARRANTY OF ANY
KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT OF
THIRD PARTY RIGHTS.

IN NO EVENT SHALL THE COPYRIGHT HOLDER OR HOLDERS INCLUDED IN THIS NOTICE
BE LIABLE FOR ANY CLAIM, OR ANY SPECIAL INDIRECT OR CONSEQUENTIAL DAMAGES,
OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS,
WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION,
ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THE DATA
FILES OR SOFTWARE.

Except as contained in this notice, the name of a copyright holder shall
not be used in advertising or otherwise to promote the sale, use or other
dealings in these Data Files or Software without prior written
authorization of the copyright holder.

SPDX-License-Identifier: Unicode-3.0
```

## Inter 4.1

- Source: https://github.com/rsms/inter (release `Inter-4.1.zip`, `extras/ttf/Inter-{Regular,SemiBold,Bold}.ttf`).
  Copyright (c) 2016 The Inter Project Authors.
- Licence: SIL Open Font License 1.1 (full text below, also at `crates/rovsti_app_core/assets/fonts/OFL.txt`).
- Used for: the fonts bundled in the Rust core for share cards (`crates/rovsti_app_core/assets/fonts/`, loaded with
  `include_bytes!`). The font files are unmodified; the name "Inter" is not a Reserved Font Name in this release.

```text
Copyright (c) 2016 The Inter Project Authors (https://github.com/rsms/inter)

This Font Software is licensed under the SIL Open Font License, Version 1.1.
This license is copied below, and is also available with a FAQ at:
http://scripts.sil.org/OFL

-----------------------------------------------------------
SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
-----------------------------------------------------------

PREAMBLE
The goals of the Open Font License (OFL) are to stimulate worldwide
development of collaborative font projects, to support the font creation
efforts of academic and linguistic communities, and to provide a free and
open framework in which fonts may be shared and improved in partnership
with others.

The OFL allows the licensed fonts to be used, studied, modified and
redistributed freely as long as they are not sold by themselves. The
fonts, including any derivative works, can be bundled, embedded,
redistributed and/or sold with any software provided that any reserved
names are not used by derivative works. The fonts and derivatives,
however, cannot be released under any other type of license. The
requirement for fonts to remain under this license does not apply
to any document created using the fonts or their derivatives.

DEFINITIONS
"Font Software" refers to the set of files released by the Copyright
Holder(s) under this license and clearly marked as such. This may
include source files, build scripts and documentation.

"Reserved Font Name" refers to any names specified as such after the
copyright statement(s).

"Original Version" refers to the collection of Font Software components as
distributed by the Copyright Holder(s).

"Modified Version" refers to any derivative made by adding to, deleting,
or substituting -- in part or in whole -- any of the components of the
Original Version, by changing formats or by porting the Font Software to a
new environment.

"Author" refers to any designer, engineer, programmer, technical
writer or other person who contributed to the Font Software.

PERMISSION AND CONDITIONS
Permission is hereby granted, free of charge, to any person obtaining
a copy of the Font Software, to use, study, copy, merge, embed, modify,
redistribute, and sell modified and unmodified copies of the Font
Software, subject to the following conditions:

1) Neither the Font Software nor any of its individual components,
in Original or Modified Versions, may be sold by itself.

2) Original or Modified Versions of the Font Software may be bundled,
redistributed and/or sold with any software, provided that each copy
contains the above copyright notice and this license. These can be
included either as stand-alone text files, human-readable headers or
in the appropriate machine-readable metadata fields within text or
binary files as long as those fields can be easily viewed by the user.

3) No Modified Version of the Font Software may use the Reserved Font
Name(s) unless explicit written permission is granted by the corresponding
Copyright Holder. This restriction only applies to the primary font name as
presented to the users.

4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font
Software shall not be used to promote, endorse or advertise any
Modified Version, except to acknowledge the contribution(s) of the
Copyright Holder(s) and the Author(s) or with their explicit written
permission.

5) The Font Software, modified or unmodified, in part or in whole,
must be distributed entirely under this license, and must not be
distributed under any other license. The requirement for fonts to
remain under this license does not apply to any document created
using the Font Software.

TERMINATION
This license becomes null and void if any of the above conditions are
not met.

DISCLAIMER
THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE
COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL
DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM
OTHER DEALINGS IN THE FONT SOFTWARE.
```

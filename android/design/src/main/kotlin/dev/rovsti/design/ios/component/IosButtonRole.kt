/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/ios/component/IosButton.kt
 * Changes for Rovsti: only the `IosButtonRole` enum (line 54 of the original) is ported here, because `IosMenuItem` needs
 * it; the rest of IosButton.kt arrives with E1.29 and takes this enum over unchanged.
 */
package dev.rovsti.design.ios.component

/** SwiftUI's `ButtonRole`: what a destructive or cancelling control looks like. */
enum class IosButtonRole { Cancel, Destructive, Confirm }

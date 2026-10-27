# Xiaomi (MIUI / HyperOS) support

Xiaomi phones are a primary target. Their power management kills background apps
more aggressively than AOSP. That breaks exactly what these apps depend on:
incoming calls, live WhatsApp sockets and arrival alarms.

## Detection

`rovsti_android::xiaomi::detect()` reads system properties directly in Rust:

- `ro.mi.os.version.name` → HyperOS (`OS1.0`, `OS2.0`, …)
- `ro.miui.ui.version.name` → MIUI (`V125`, `V140`, …)

## First-run checklist (shown only on Xiaomi)

| Step | Why | Deep link (`xiaomi::open`) |
| --- | --- | --- |
| Autostart | The app is restarted for broadcasts (SMS, boot) and is not killed when swiped from Recents | `com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity` |
| Battery saver → No restrictions | Foreground services survive screen-off | `com.miui.powerkeeper/.ui.HiddenAppsConfigActivity` (plus the AOSP battery-optimisation dialog) |
| Other permissions → Show on lock screen, Display pop-up windows while running in background | Incoming-call UI and the arrival alarm can appear over the lock screen | `com.miui.securitycenter/com.miui.permcenter.permissions.PermissionsEditorActivity` |

Every link falls back to the app's system details page when a component is missing,
because Xiaomi moves these screens between releases.

## Known quirks to verify on device

- Some MIUI builds hide the default-app role dialog. When `roles::request` returns
  `false`, send the user to Settings → Apps → Manage apps → Default apps.
- HyperOS can revoke "Autostart" after an app update. Re-check it on every launch,
  not only the first.
- Full-screen intents need `USE_FULL_SCREEN_INTENT`. On Android 14+ the permission
  is pre-granted only to dialer and alarm apps. Stops must ask for it.
- GPU: GPUI uses Vulkan first and falls back to GLES. Older Mali GPUs in Redmi phones
  may need the GLES path. Check `adb logcat -s GPUI`.

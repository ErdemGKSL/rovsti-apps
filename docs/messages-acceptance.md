# Rovsti Messages: device checks

For the person testing on the Xiaomi phone (HyperOS). Nothing here runs in CI: the Rust and Kotlin
unit tests cover the logic, and these checks cover what only a real phone can show. Mark each row
Pass or Fail and write what you saw next to every Fail. You need a second phone to send you SMS and
MMS, and a WhatsApp account linked to the gateway for rows 11 to 13.

APK: build with `gpuiforge build android` in `apps/messages`. Put `google-services.json` in
`platforms/android/app/` first for rows 11 to 13. Read logs with `adb logcat -s GPUI RovstiBridge`.

| # | Step | Expected | Pass / Fail | Notes |
|---|---|---|---|---|
| **A. Role grant on HyperOS** | | | | |
| 1 | Install and open Rovsti Messages. Allow SMS, Contacts, Phone and Notifications when asked. | The permissions are granted. On HyperOS "Allow restricted settings" may be needed first (Settings, Apps, Rovsti Messages, three dots). | | |
| 2 | Tap the default-app prompt (Settings, Apps, Default apps, SMS app) and choose Rovsti Messages. | The system dialog offers Rovsti Messages (it appears only if all four role components resolve) and the role is granted. | | |
| 3 | Reopen the app. | No "make default" banner; sending is enabled. | | |
| **B. SMS received with the app not running (`SMS_DELIVER`, cold)** | | | | |
| 4 | Swipe the app away from Recents (and use Force stop). Send an SMS from the second phone. | A notification with the text arrives within seconds, with a Reply action and Mark read. | | |
| 5 | Send "Your code is 482913". | The notification shows a Copy code action and no Reply. Tapping it copies 482913. | | |
| 6 | Open the app. | The message is in the thread and unread; opening the notification opens that thread. | | |
| 7 | Add a spam keyword rule in Settings, force stop, send a message with the keyword from a number that is not a contact. | No notification. The message is in the Spam folder, not deleted. | | |
| **C. Inline reply, cold** | | | | |
| 8 | Force stop the app, receive an SMS, type a reply in the notification and send. | The second phone gets the SMS. The notification updates with "You: ..." and the spinner stops. | | |
| **D. Scheduled SMS** | | | | |
| 9 | Schedule an SMS two minutes ahead, force stop the app, wait. | It is sent on time and shows as sent in the thread. | | |
| 10 | Settings, Apps, Special access, Alarms & reminders: deny Rovsti Messages. Schedule another SMS two minutes ahead and reboot the phone. | The UI says it may be a few minutes late. After boot the alarm is re-armed and the SMS is sent (late is fine, lost is a Fail). | | |
| **E. WhatsApp digest, cold (FCM)** | | | | |
| 11 | Force stop the app. Have a WhatsApp contact message the linked account. | A notification arrives that shows the real message text (not "New message"). | | |
| 12 | Reply inline from that notification. | The contact receives it on WhatsApp; the notification updates. | | |
| 13 | Open the app, then have the contact message again. | One notification, posted by the app itself, no duplicate from the push. | | |
| **F. Native library from a receiver** | | | | |
| 14 | `adb logcat` while doing row 4 from a cold process. | No `UnsatisfiedLinkError`. `System.loadLibrary("rovsti_messages")` succeeds in `SmsDeliverReceiver` and the passcode/spam answer is used (row 5 and 7). | | |
| 15 | Receive a picture message (MMS) with the app force-stopped. | The message is downloaded, stored in the thread with its picture, and a notification shows the text or "Photo". | | |
| 16 | Share text and a photo to Rovsti Messages from another app, and tap an `sms:` link. | The app opens a new conversation (SMS link) or the chooser for the shared item. | | |

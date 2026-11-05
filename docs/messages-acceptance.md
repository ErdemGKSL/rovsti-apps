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
| **G. Notifications while the app is open** | | | | |
| 17 | Open the app on the inbox. Have the second phone send an SMS. | One notification from the app itself (no duplicate from Kotlin) with the text, a Reply action and Mark read. | | |
| 18 | Open that conversation, then receive another SMS. | No notification for the open thread. Leaving the thread and receiving again notifies. | | |
| 19 | Receive an SMS from a number in your contacts and from an unknown number with a link. | The contact notifies. The unknown link goes to Spam and does not alert. | | |
| 20 | Tap the bell in a conversation, then receive a message there. | No notification; Settings, Notifications lists it as muted. Tap it there to unmute. | | |
| 21 | Open a conversation that has a notification. | The notification is removed. | | |
| 22 | With the app open, reply from an SMS notification and from a WhatsApp notification. | Both go out. If that conversation is open the reply shows in its timeline. | | |
| **H. Send later** | | | | |
| 23 | Type text in an SMS conversation and long-press Send. | The "Send later" sheet lists In 1 hour, This evening or Tomorrow evening, and Tomorrow morning, with a custom time field. | | |
| 24 | Pick a time. | The text leaves the composer, a Scheduled strip shows it with Cancel, and a pill says when. | | |
| 25 | Type 18:30 or 2026-12-31 09:00 as a custom time; then a time in the past. | The first is accepted; the second shows "Pick a time at least a minute from now". | | |
| 26 | Cancel the scheduled item from the strip, force stop, wait past its time. | Nothing is sent. | | |
| 27 | Do the same in a WhatsApp conversation, then close the app. | The strip shows the gateway item; it is sent on time; Cancel removes it. | | |
| 28 | Schedule an SMS a few minutes ahead, switch to airplane mode through its time, then reopen the app. | A pill says the scheduled message could not be sent, and it is gone from the strip. | | |
| **I. Settings** | | | | |
| 29 | Settings tab with Rovsti Messages not default. | "Default SMS app" says Off; tapping it asks for the role. | | |
| 30 | Add a Sender rule (PROMO*) and a Text contains rule; delete one. | Rules list and persist after a restart. A matching SMS from a non-contact goes to Spam. | | |
| 31 | Add a sender to the allow list. | Messages from it never go to Spam. | | |
| 32 | Block a number; unblock it from the list. | It appears in Phone's blocked numbers too, and leaves when unblocked. | | |
| 33 | Switch off Copy code, then receive "Your code is 482913". | A notification without the Copy action and without Reply. | | |
| **J. Encrypted backup** | | | | |
| 34 | Export: type two different passphrases. | "The two passphrases are different." | | |
| 35 | Export with a short passphrase twice. | The first press warns it is weak; pressing Export again continues. | | |
| 36 | Export with a strong passphrase and choose a folder. | A `rovsti-messages-YYYYMMDD.rmb` file is saved and the page says so. The file is not readable text. | | |
| 37 | Import that file with a wrong passphrase. | "Wrong passphrase, or the backup was changed." and the field stays for another try. | | |
| 38 | Import it with the right passphrase on the same phone. | The preview says 0 messages will be added and N already on this phone. | | |
| 39 | Delete a thread, import the file again (role held). | The preview lists the missing messages; Restore brings them back, once. | | |
| 40 | Open the file in a text editor, change a byte, import. | "Wrong passphrase, or the backup was changed." | | |
| **K. Desktop preview** | | | | |
| 41 | `cargo run -p rovsti_messages`: open Settings, right-click Send with text in a conversation, export a backup. | Settings sections show; the sheet opens; the export writes `cache/rovsti-messages-*.rmb` in `~/.local/share/rovsti/messages` and the page names the path. | | |
| **L. WhatsApp media** | | | | |
| 42 | In a WhatsApp chat tap the attach button, pick a 5 MB photo, type a caption, press Send. Then attach a PDF and send it. | A chip with the name and "Photo" shows above the composer; the photo shows in the bubble at once; both arrive on the other phone (the caption with the photo). |  | |
| 43 | Try to attach a 20 MB file. | It is refused ("Couldn't attach that file" from the picker's 8 MB cap, or "That file is over 16 MB"); nothing is sent. | | |
| 44 | Attach a file, switch the gateway off, press Send, switch it on, tap the bubble. | "Not sent · Tap to retry"; the retry sends the file. | | |

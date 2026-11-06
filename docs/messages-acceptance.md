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
| 1 | Install and open Rovsti Messages. Tap Make default (it grants SMS, Contacts, Phone and Notifications). If any is still off, tap the inbox banner, the Allow notifications and Contacts rows in Settings, or the Contacts line in New message. | The permissions are granted. On HyperOS "Allow restricted settings" may be needed first (Settings, Apps, Rovsti Messages, three dots). | | |
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
| **M. Managing conversations** | | | | |
| 45 | Long press an SMS row, choose Delete, confirm. | The row asks first; after Delete the conversation is gone and a pill says so. Cancel keeps it. | | |
| 46 | Long press a spam row, choose Not spam; tap Undo. | The row leaves Spam with a pill; Undo puts the sender back in Spam. | | |
| 47 | Long press a row, choose Block; tap Undo. | The number is blocked (also in Phone) and Undo unblocks it. Emergency numbers are refused. | | |
| 48 | Long press a message bubble, choose Copy, or Copy code on an OTP. | The text is on the clipboard and a pill says Copied; the same Copy pill shows in WhatsApp chats. | | |
| 49 | Open the menu in an SMS page: Call, Add to contacts. | The dialer or the contact editor opens with the number. | | |
| 50 | Type a draft, press Back, open the conversation again. | The draft is still there and the inbox row shows Draft. | | |
| 51 | Tap search in Chats and type part of a name or text. | Only matching conversations show; no match shows No Results; Back clears the search. | | |
| 52 | In New message pick a contact with two numbers. | Both numbers are listed with their labels. | | |
| 53 | With WhatsApp linked, pick a contact in New message. | A menu offers SMS or WhatsApp; WhatsApp opens the chat, or says the person is not on WhatsApp. | | |
| 54 | In Settings delete a rule, remove an allowed sender, unblock a number, unmute a conversation. | Each shows an Undo pill; Undo puts the rule back at the same position, re-allows, re-blocks or re-mutes. | | |
| 55 | In a WhatsApp chat tap a received document, video or audio (long press an image and choose Save). | The file is saved through the system Save as dialog and a pill says Saved. | | |
| 56 | Long press a blocked sender's row and choose Unblock. | The number is unblocked and an Undo pill re-blocks it. Block is not offered for a blocked number. | | |
| 57 | While Messages is not the default SMS app, long press a row and choose Delete. | A pill offers Make default and opens the role dialog; no confirmation opens first. | | |
| 58 | Open a WhatsApp chat and use the menu next to the bell. | Pin, Archive, Mute and Mark unread work; a 1:1 chat also offers Call and Add to contacts (Open contact for a saved person). | | |
| 59 | Long press a message in SMS and WhatsApp and choose Share; in an SMS page open a saved contact from the menu. | The system share sheet opens with the text; Open contact opens the contact editor. | | |
| 60 | Schedule four or more messages in one chat, tap Show more, then Cancel one while the composer is empty. | All items are listed, and the cancelled text returns to the composer (a typed text is never replaced). A pill says Scheduled message cancelled. | | |
| 61 | Leave a chat with a half-written message. | The inbox row reads Draft: followed by the first line. | | |
| 62 | Mute a few chats and open Settings. | Muted entries show contact or chat names, not raw numbers. | | |
| 63 | Long press Send, use the - and + rows for the day and the time, tap Schedule. | The time moves in 15-minute steps (wrapping at midnight), the day up to 30 days ahead; the message is scheduled for the shown time. A past time is refused. The typed field still works. | | |
| 64 | In a WhatsApp chat type text and press the keyboard Send key. | The message is sent, same as the Send button. | | |
| 65 | Make a message fail (airplane mode), then long press the failed bubble. | Copy and Delete are offered next to Retry; Delete removes it. | | |
| 66 | Open a received MMS picture or file in an SMS conversation. | A Save pill saves it through the system Save as dialog. | | |
| 67 | Receive a message containing a link and long press it. | An Open link pill opens the link in the browser. | | |
| 68 | Archive a WhatsApp chat; mute an SMS conversation. | A pill confirms each and offers Undo. | | |
| 69 | Log the WhatsApp gateway out or go offline, open the inbox. | A banner says WhatsApp is offline or logged out; tapping it opens the WhatsApp tab. | | |
| 70 | Open a conversation whose load failed. | The error text is tappable and retries the load. | | |
| 71 | Without the default SMS role, open a backup in Settings and reach the preview. | The preview says the role is needed and shows Make default instead of Restore; after granting it, Restore appears without typing the passphrase again. | | |
| 72 | Change spam rules and switches, then Settings, Reset settings, confirm with Reset. | Settings go back to the defaults (SIM choices stay, blocked numbers stay) and an Undo pill brings everything back. The Reset section is hidden when nothing differs. | | |
| 73 | Without the default SMS role on Android 13 or newer, with Notifications off for the app in system settings, open the inbox. Tap the banner, then Allow. Also try Settings, Notifications, Allow notifications. | The banner says Android has notifications off. Allow shows the system prompt; if it was refused twice, App info opens instead. The banner disappears once allowed, and it stays hidden when the app's own Notifications switch is off. | | |
| 74 | Deny Contacts, then open Settings and New message. | Settings shows a Contacts row and New message shows a line above the results saying contacts access is off. Tapping either asks for it (or opens App info). After allowing, names replace numbers. | | |
| 75 | Turn SMS access off, open the inbox, tap Open settings, allow in App info and press Back. | The list refreshes by itself on return: the access-off message goes away and threads (and names, if Contacts was allowed too) appear without restarting the app. | | |
| 76 | Receive a WhatsApp location and tap the bubble, or long press it and choose Open in Maps. Also try Copy. | The maps app opens at the shared point (with its name when it has one). Copy puts the name, address and coordinates on the clipboard. A tap while the pill strip is open only closes the strip. | | |
| 77 | Receive a WhatsApp contact card and long press it. | Call, Message, Add to contacts and Copy are offered. Call dials, Message opens the chat (with a channel pick when WhatsApp is linked), Add to contacts opens the editor, or the existing contact when the number is known. | | |
| 78 | Mute a WhatsApp chat from the row menu, then open it. Then unmute with the bell. Repeat in the other order (bell first, then check the row). Open Settings, Muted. | The bell inside the chat and the bell-off on the row always agree. The row menu offers Unmute whichever way it was muted, and Unmute ends both. Settings, Muted lists chats muted on the gateway too, and tapping one unmutes it with an Undo pill. | | |
| 79 | Open a conversation that is in Spam. | A line says this conversation is in Spam with a Not spam button, and the page menu offers Not spam first. Tapping it moves the thread out of Spam, shows an Undo pill and the line goes away; Undo brings it back. | | |
| 80 | Schedule an SMS a minute ahead, go offline or deny the send so it fails, and wait. | A pill names the recipient (or counts several) and offers Open. Open shows the SMS conversation with the text in the composer, ready to Send or Send later. | | |
| 81 | On Android 14 with Alarms and reminders off for the app, open Send later for an SMS (and for a WhatsApp message). | The SMS sheet says Android may send it a few minutes late; tapping the line opens App info. The WhatsApp sheet has no such line. After allowing alarms the line is gone. | | |
| 82 | Turn the gateway off and react to a WhatsApp message. | The reaction goes back to what it was (or disappears) and the line says Couldn't send the reaction, with no technical text. | | |
| 83 | Edit a WhatsApp message while the gateway is off and press Send. | The edited text stays in the composer, still in edit mode, with a notice that sending the edit failed and Send tries again. | | |
| 84 | Make loading WhatsApp chats fail (gateway off), then bring it back. | A pill says Couldn't load WhatsApp chats and offers Retry; Retry loads the chats. | | |

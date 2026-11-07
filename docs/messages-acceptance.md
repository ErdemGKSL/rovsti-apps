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
| 50 | Type a draft, press Back, open the conversation again. | The draft is still there and the inbox row shows Draft. Closing the app (or sending it to the background) and reopening keeps it too. | | |
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
| 85 | In the inbox, long press an SMS row from an alphanumeric sender (for example TRENDYOL) that is not a contact. Choose Move to Spam. | The menu offers Move to Spam but no Block, Call or Add to contacts. A pill says Moved TRENDYOL to Spam with Undo; the row leaves the inbox and appears in Spam. Undo brings it back. Settings, Spam shows Sender: TRENDYOL while moved. | | |
| 86 | Open the same sender's conversation and use the page menu, Move to Spam. | The conversation stays open and shows the line that it is in Spam with Not spam. Move to Spam is hidden for contacts, rows already in Spam and rows that also have a WhatsApp chat. | | |
| 87 | Fill the spam rules to the limit in Settings, then try Move to Spam. | A pill says Too many spam rules and to remove one in Settings first; nothing changes. | | |
| 88 | Type half a message in an SMS and a WhatsApp conversation, send the app to the background, force-stop it from App info and reopen. | Both drafts are back in their composers and the inbox rows show Draft:. After sending, the draft is gone after another restart. | | |
| 89 | Type a message, open the page menu and choose Send later. Then try it with an empty composer. | With text, the Send later sheet opens (SMS needs the default role). With an empty composer a notice says to type the message first, then choose Send later. A long press on Send with an empty composer shows the same notice. | | |
| 90 | Schedule an SMS to a new number (no thread yet) and a WhatsApp message, then open the inbox. | A Scheduled row appears with the count (for example 2 waiting). It lists both items with contact name or number, time and preview. Tapping an item opens its conversation; Cancel there puts the text back in the composer. Back returns to the inbox. The row is gone when nothing is scheduled. | | |
| 91 | Make a scheduled WhatsApp message fail on the gateway and look at the Scheduled list. | The failed item is listed first in orange as Couldn't be sent, and the Scheduled row subtitle counts it. | | |
| 92 | Let a scheduled WhatsApp message fail while another screen is open. | A pill says Scheduled WhatsApp message to the name couldn't be sent with Open. Open shows the chat with the failed item; Cancel restores the text to the composer. No pill when that chat is already open. | | |
| 93 | Open a contact's WhatsApp Status. | A Message button shows at the bottom. Tapping it closes the viewer and opens that chat; tapping elsewhere still steps the viewer. Your own updates and senders without a phone number have no Message button. | | |
| 94 | Archive a WhatsApp chat, then type its name in the inbox search. | The archived chat is found and listed after the normal matches; its row menu offers Unarchive. No Results shows only when nothing matches in either list. | | |
| **H. Round 2 utilities** | | | | |
| 95 | Long press a WhatsApp text bubble, then Forward. Pick another chat (use the search field), then repeat with a picture bubble and with an SMS that has text. | The chooser lists one entry per thread and not the open chat. A Forwarding pill appears, then a result pill with Open that opens the target chat. Text arrives in a WhatsApp or SMS target, the picture arrives with its caption. Forwarding by SMS without the default role says to set Rovsti as the SMS app. Empty text and no picture says Nothing to forward. | | |
| 96 | Open a picture in the full-screen viewer and tap Forward. | The viewer closes and the same chooser opens; the picture arrives in the chosen chat. Forward is dimmed until the full file has loaded. | | |
| 97 | Star two messages in a WhatsApp chat, open the page menu, Starred messages. | A sheet lists the starred messages with author, preview and date. Tapping a row closes the sheet and jumps to the message with a glow. The star button unstars it and shows Unstarred. With none starred the empty text appears, and Load earlier is offered when older messages exist. Back closes the sheet first. | | |
| 98 | Settings, Quick replies: add a reply, move one up, delete one and tap Undo. Try adding an empty or duplicate reply. | Added shows Reply added; delete shows Reply removed with Undo that restores it at the same place. Empty and duplicate replies show an error pill and change nothing. Reset settings keeps the replies. | | |
| 99 | In a WhatsApp and an SMS conversation tap the lightning button next to the composer, then a chip. With an empty list tap the single chip. | A chip row opens above the composer; a chip inserts its text (after existing text with a space), closes the row and keeps focus. With no replies the chip says Add quick replies in Settings and opens Settings. | | |
| 100 | Open a chat with many messages, scroll up, then have the other phone send two messages. | A round arrow button appears at the bottom right with a count of 2. Tapping it scrolls to the end and the button disappears. It is hidden when you are at the bottom. | | |
| 101 | Leave a chat with 5 unread messages unopened, then open it. | The list opens at a capsule saying 5 unread messages, above the first unread message, instead of at the end. Sending a message or leaving removes it. A chat with nothing unread opens at the end. | | |
| 102 | Long press a message and choose Info (WhatsApp sent, WhatsApp received, SMS, MMS). | A sheet shows time, state (Sent, Delivered, Read, Received, Failed or Pending), edited and forwarded when they apply, length and segments for SMS, SIM name for SMS and the media type with size or duration. Copy puts the lines on the clipboard and shows a pill. | | |
| 103 | Long press a text message with several words and choose Select text. Drag over some words, then Copy selection; then Copy all; then Copy selection with nothing selected. | The text is shown in a sheet and can be selected by dragging. Copy selection copies only the selection, Copy all copies everything, both with a Copied pill. With no selection a pill says Select some text first. The entry is hidden for one-word messages. | | |
| 104 | In the inbox tap the avatar of a row whose contact has a photo, then one with only a WhatsApp picture. | The photo opens full screen in the viewer (pinch, double-tap, pan, Save, Share work). Back returns to the inbox, not the chat. | | |
| 105 | In the inbox tap the avatar of a row with only initials, then tap the row's text area, and long press the avatar. | The avatar tap opens the chat like the rest of the row. The row text tap opens the chat. The long press still opens the row menu. A photo tap never also opens the chat. | | |
| 106 | Desktop build only: use Save and Share in the viewer and Open in the forward pill flows that need Android. | A pill says the action is not supported on this device (Error::Unsupported); nothing crashes and nothing is changed. | | |
| **I. Round 3 utilities** | | | | |
| 107 | Open a conversation, page menu, Media, links and docs. Switch tabs, tap a picture, a link and a document. | Tabs Media, Links and Docs list the chat's items newest first under month headers. A picture opens the viewer, a link opens the browser, a document row saves or jumps to its message. Empty tabs say so. Back closes the sheet first. (M13) | | |
| 108 | Long press a message containing a link and tap Copy link; open the Links tab and look at a very long URL. | A pill says Link copied and the URL is on the clipboard. The gallery shortens long links but still opens the full URL. (M25) | | |
| 109 | Long press a received SMS and tap Delete. Tap Cancel, then tap Delete again and confirm with Delete. | The sheet asks Delete this message? It is removed from this phone. Cancel changes nothing. Confirming hides the message at once and a pill says Message deleted with Undo. Delete is also offered on MMS. (M15) | | |
| 110 | Delete an SMS, tap Undo within 5 seconds. Delete another and wait 6 seconds. | Undo brings the message back at its place and nothing is deleted. Without Undo the message is deleted from the provider and does not return after reopening the chat; the inbox preview updates. (M15) | | |
| 111 | Delete an SMS and press Back to the inbox at once; then delete another and send the app to the background. | The pending deletes are committed straight away, without waiting for the 5 seconds. (M15) | | |
| 112 | Without the default SMS role, long press an SMS and tap Delete. | No confirm sheet; a pill says Make Rovsti Messages your default SMS app to delete messages with Make default. (M15) | | |
| 113 | Make the provider delete fail (revoke the role while the Undo window runs). | A pill says Couldn't delete that message and the message reappears. (M15) | | |
| 114 | In a WhatsApp group, tap an incoming message from a member with a phone number and tap Message {first name}. Try an own message and a member known only by a LID. | The 1:1 chat with that member opens (created when needed). The pill is missing on own messages and on senders without a phone number. (M22) | | |

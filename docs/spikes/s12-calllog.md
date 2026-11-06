# Spike S12: call log write (stories 21.2 and 21.3)

Status: implemented and tested without a device, **waiting for the owner's phone run**. The
Recents row menu, the "Delete call history" button in the Number and Contact sheets and Settings,
Data, "Delete all call history" use `CallLogWrite.kt` (`delete`, `restore`, `deleteAll`). It needs
`WRITE_CALL_LOG` (declared in the manifest) and the app being the default phone app. HyperOS may
still refuse writes to `CallLog.Calls`.

## Owner procedure

Use a spare test call, never the only record of something you need.

1. Make a short call to a second phone so the log has a marked row (note its time).
2. Recents: hold that row, tap Delete. The row disappears and the pill "Deleted 1 call" shows.
   Check the system Dialer's log too: the row must be gone there as well.
3. Tap Undo before the pill ends. The row returns in Recents with the same type, time, duration
   and SIM. Compare it with the note from step 1.
4. Delete it again and let the pill run out. Reopen the app: the row stays gone.
5. Optional: Settings, Data, "Delete all call history", confirm. Recents is empty.
   (Do this last; the restore path is only covered by step 3.)
6. Take the call log permission away in system settings and repeat step 2: the pill must offer
   "Allow call log access".

## Shipped default and fallback

Deleting and Undo are the default. If the phone refuses the write (insert or delete returns 0
rows), the pill reads "Not allowed on this phone", nothing changes and no Undo is offered. If
only the restore is refused, Undo shows "Couldn't restore" and the WhatsApp records are still
put back. There is no other fallback: Phone does not hide rows it cannot delete.

## Result (fill in from the phone)

| Row | Result | Value | Note |
| --- | --- | --- | --- |
| Inserted marked row appears in Recents and the system Dialer | PENDING | | |
| Delete removes it from the system log | PENDING | | |
| Restore re-creates type, time, duration and SIM | PENDING | | |
| Delete again sticks after reopening the app | PENDING | | |
| Delete all empties the log | PENDING | | |
| Missing permission shows "Allow call log access" | PENDING | | |
| Refusal shows "Not allowed on this phone", no Undo | PENDING | | |

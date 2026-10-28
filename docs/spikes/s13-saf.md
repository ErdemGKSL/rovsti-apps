# Spike S13: Storage Access Framework pickers (story 16.6)

Status: instrumented, **waiting for the owner's phone run**. The Settings Data rows (Export,
Import) use the pickers; no storage permission is requested.

## What is measured

Diagnostics -> group **Backup**. It writes a tiny test file through the system "create file"
picker (`ACTION_CREATE_DOCUMENT`), then asks for the same file through the "open file" picker
(`ACTION_OPEN_DOCUMENT`) and compares the text. It prints `pass`, `fail` or `unknown` lines.

## Owner procedure

1. Settings -> Data -> Export favorites and settings. Pick a place (Downloads is fine). The pill
   says "Saved to rovsti-phone-backup-{date}.json".
2. Settings -> Data -> Import, choose that file: the Import preview sheet lists what it replaces.
   Cancel it.
3. Diagnostics -> Backup -> Run: save the test file when asked, then choose it when asked again.
4. Import a text file that is not a backup (any .txt): the red row "This isn't a Rovsti Phone
   backup." appears and nothing changes.

## Shipped default and fallback

The pickers are the default. If either picker is missing or refused on HyperOS, the fallback is
"Copy backup" and "Paste backup" through the clipboard, built only if this check fails.

## Result (fill in from the phone)

| Row | Result | Value | Note |
| --- | --- | --- | --- |
| Export picker saves a file, no permission asked | PENDING | | |
| Import picker returns the same text | PENDING | | |
| Foreign file is refused with the red row | PENDING | | |

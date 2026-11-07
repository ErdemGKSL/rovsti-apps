//! Export of a chat as plain text, for the share sheet or a saved file.

use crate::call_log::{MONTHS_SHORT, civil_from_days, day_key};
use crate::number_format::file_slug_opt;

const SECS_PER_DAY: i64 = 86_400;

/// One message of the export, oldest first.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ExportLine<'a> {
    pub at_ms: i64,
    pub author: &'a str,
    pub text: &'a str,
}

fn date_label(day: i64) -> String {
    let (year, month, d) = civil_from_days(day);
    format!("{d} {} {year}", MONTHS_SHORT[(month as usize - 1) % 12])
}

fn iso_date(day: i64) -> String {
    let (year, month, d) = civil_from_days(day);
    format!("{year:04}-{month:02}-{d:02}")
}

/// The chat as text: a header, a "-- 12 Nov 2026 --" line at each local day
/// change and "[14:05] Ada: text" per message. Continuation lines are indented
/// by two spaces, blank messages are skipped, the text ends with a newline.
pub fn export_text(title: &str, lines: &[ExportLine<'_>], tz_offset_s: i32) -> String {
    let mut out = format!("Chat with {title}\n");
    let mut day = None;
    for line in lines {
        if line.text.trim().is_empty() {
            continue;
        }
        let d = day_key(line.at_ms, tz_offset_s);
        if day != Some(d) {
            day = Some(d);
            out.push_str(&format!("\n-- {} --\n", date_label(d)));
        }
        let secs = (line.at_ms.div_euclid(1000) + i64::from(tz_offset_s)).rem_euclid(SECS_PER_DAY);
        let (h, m) = (secs / 3600, secs % 3600 / 60);
        let mut parts = line.text.trim_end().lines();
        let first = parts.next().unwrap_or_default();
        out.push_str(&format!("[{h:02}:{m:02}] {}: {first}\n", line.author));
        for rest in parts {
            if rest.trim().is_empty() {
                continue;
            }
            out.push_str("  ");
            out.push_str(rest);
            out.push('\n');
        }
    }
    out
}

/// "chat-ada-2026-11-12.txt", or "chat-2026-11-12.txt" without a usable title.
pub fn export_file_name(title: &str, now_ms: i64, tz_offset_s: i32) -> String {
    let date = iso_date(day_key(now_ms, tz_offset_s));
    match file_slug_opt(title) {
        Some(slug) => format!("chat-{slug}-{date}.txt"),
        None => format!("chat-{date}.txt"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // 2026-11-12 14:05:00 UTC
    const T: i64 = 1_794_492_300_000;
    const DAY: i64 = 86_400_000;

    fn line<'a>(at_ms: i64, author: &'a str, text: &'a str) -> ExportLine<'a> {
        ExportLine {
            at_ms,
            author,
            text,
        }
    }

    #[test]
    fn export_groups_by_local_day() {
        let lines = [
            line(T, "Ada", "hi"),
            line(T + 60_000, "Me", "hello"),
            line(T + DAY, "Ada", "next"),
        ];
        let out = export_text("Ada", &lines, 0);
        assert_eq!(
            out,
            "Chat with Ada\n\n-- 12 Nov 2026 --\n[14:05] Ada: hi\n[14:06] Me: hello\n\n-- 13 Nov 2026 --\n[14:05] Ada: next\n"
        );
        // +10h pushes the first message to the next local day.
        let out = export_text("Ada", &lines[..1], 10 * 3600);
        assert!(out.contains("-- 13 Nov 2026 --\n[00:05] Ada: hi"));
    }

    #[test]
    fn export_indents_multiline() {
        let out = export_text("A", &[line(T, "Ada", "one\ntwo\n\nthree")], 0);
        assert!(out.ends_with("[14:05] Ada: one\n  two\n  three\n"));
    }

    #[test]
    fn export_skips_blank() {
        let out = export_text("A", &[line(T, "Ada", "  \n")], 0);
        assert_eq!(out, "Chat with A\n");
    }

    #[test]
    fn file_name_slugs_title() {
        assert_eq!(
            export_file_name("Ada Lovelace!", T, 0),
            "chat-Ada-Lovelace-2026-11-12.txt"
        );
    }

    #[test]
    fn file_name_without_title() {
        assert_eq!(export_file_name(" ?? ", T, 0), "chat-2026-11-12.txt");
    }
}

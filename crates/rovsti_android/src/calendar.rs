//! Add-to-calendar through the system calendar editor. Kotlin side:
//! `RovstiBridge.calendarInsert`, an `ACTION_INSERT` `CalendarContract` intent,
//! so no calendar permission is needed and the user confirms in their own
//! calendar app. On desktop every function returns [`crate::Error::Unsupported`].

use crate::{
    Result,
    bridge::{self, Arg},
};

/// Opens the calendar editor prefilled with an event. Times are epoch
/// milliseconds (they cross the seam as decimal strings). `false` when no
/// calendar app could handle the intent.
pub fn insert_event(title: &str, begin_ms: i64, end_ms: i64, all_day: bool) -> Result<bool> {
    bridge::call_bool(
        "calendarInsert",
        &[
            Arg::Str(title),
            Arg::Str(&begin_ms.to_string()),
            Arg::Str(&end_ms.to_string()),
            Arg::Bool(all_day),
        ],
    )
}

#[cfg(all(test, not(target_os = "android")))]
mod tests {
    use super::*;
    use crate::Error;

    #[test]
    fn desktop_is_unsupported() {
        assert!(matches!(
            insert_event("Dinner", 1_000, 2_000, false),
            Err(Error::Unsupported)
        ));
        assert!(matches!(
            insert_event("Holiday", 0, 86_400_000, true),
            Err(Error::Unsupported)
        ));
    }
}

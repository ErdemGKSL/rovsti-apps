//! Setup checklist derivation (FR-5..FR-8, AD-14).
//!
//! The Android side reports readable [`SetupFacts`]; this module turns them,
//! plus the stored Xiaomi confirmations, into the ordered checklist the UI
//! renders. Everything is allocation-free except [`SetupChecklist::reopened_since`].

use crate::small_text::SmallText;
use serde::{Deserialize, Serialize};

/// One row of the checklist, declared in FR-5 order.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SetupItem {
    DefaultPhoneApp,
    PhoneAndCallLog,
    Contacts,
    Notifications,
    FullScreenNotifications,
    /// Xiaomi only.
    Autostart,
    /// Xiaomi only.
    BatterySaver,
    /// Xiaomi only: "Show on lock screen".
    ShowOnLockScreen,
    /// Xiaomi only: "Display pop-up windows while running in the background".
    BackgroundPopups,
}

impl SetupItem {
    /// FR-5 order. Xiaomi items last.
    pub const ALL: [SetupItem; 9] = [
        Self::DefaultPhoneApp,
        Self::PhoneAndCallLog,
        Self::Contacts,
        Self::Notifications,
        Self::FullScreenNotifications,
        Self::Autostart,
        Self::BatterySaver,
        Self::ShowOnLockScreen,
        Self::BackgroundPopups,
    ];

    pub fn is_xiaomi(self) -> bool {
        matches!(
            self,
            Self::Autostart | Self::BatterySaver | Self::ShowOnLockScreen | Self::BackgroundPopups
        )
    }

    /// Row title.
    pub fn title(self) -> &'static str {
        match self {
            Self::DefaultPhoneApp => "Default phone app",
            Self::PhoneAndCallLog => "Phone and call log",
            Self::Contacts => "Contacts",
            Self::Notifications => "Notifications",
            Self::FullScreenNotifications => "Full-screen notifications",
            Self::Autostart => "Autostart",
            Self::BatterySaver => "Battery saver",
            Self::ShowOnLockScreen => "Show on lock screen",
            Self::BackgroundPopups => "Background pop-ups",
        }
    }

    /// Why the item matters, one line.
    pub fn reason(self) -> &'static str {
        match self {
            Self::DefaultPhoneApp => "Lets Rovsti Phone place and answer calls",
            Self::PhoneAndCallLog => "Shows your recent calls and places calls",
            Self::Contacts => "Shows names instead of numbers",
            Self::Notifications => "Shows incoming and ongoing calls",
            Self::FullScreenNotifications => "Shows the call screen over the lock screen",
            Self::Autostart => "Lets calls reach the app when it is closed",
            Self::BatterySaver => "Keeps the battery saver from stopping call alerts",
            Self::ShowOnLockScreen => "Lets the call screen appear while locked",
            Self::BackgroundPopups => "Lets the call screen open while you use another app",
        }
    }

    /// Stable key used in stored preferences.
    pub fn key(self) -> &'static str {
        match self {
            Self::DefaultPhoneApp => "default_phone_app",
            Self::PhoneAndCallLog => "phone_and_call_log",
            Self::Contacts => "contacts",
            Self::Notifications => "notifications",
            Self::FullScreenNotifications => "full_screen_notifications",
            Self::Autostart => "autostart",
            Self::BatterySaver => "battery_saver",
            Self::ShowOnLockScreen => "show_on_lock_screen",
            Self::BackgroundPopups => "background_popups",
        }
    }

    /// Inverse of [`SetupItem::key`].
    pub fn from_key(key: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|item| item.key() == key)
    }

    /// Whether a readable "denied" can be trusted over a manual confirmation.
    /// Only the autostart op is well documented; the lock-screen and
    /// background pop-up op codes are community-sourced, so the owner's
    /// confirmation wins over a "denied" reading there.
    fn reading_is_authoritative(self) -> bool {
        matches!(self, Self::Autostart)
    }

    fn bit(self) -> u16 {
        1 << (self as u8)
    }
}

/// A setting that may or may not be readable on this device.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub enum Reading {
    Granted,
    Denied,
    /// The OS gave no usable answer; fall back to the owner's confirmation.
    #[default]
    Unknown,
}

impl Reading {
    /// `Some(true)` granted, `Some(false)` denied, `None` unknown.
    pub fn from_option(value: Option<bool>) -> Self {
        match value {
            Some(true) => Self::Granted,
            Some(false) => Self::Denied,
            None => Self::Unknown,
        }
    }
}

/// What Android lets the app read about itself.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct SetupFacts {
    pub role_held: bool,
    pub phone_and_call_log_granted: bool,
    pub contacts_granted: bool,
    pub notifications_granted: bool,
    pub full_screen_allowed: bool,
    /// `PowerManager.isIgnoringBatteryOptimizations`. The Xiaomi "No
    /// restrictions" mode itself is not readable.
    pub battery_exempt: bool,
    /// Xiaomi AppOps readings (Kotlin `XiaomiOps`).
    pub autostart: Reading,
    pub lock_screen: Reading,
    pub background_popups: Reading,
}

/// State of one item.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum ItemState {
    Done,
    NotDone,
    /// Xiaomi settings cannot be read; the owner confirms them by hand.
    Unverifiable,
}

impl ItemState {
    pub fn is_done(self) -> bool {
        self == Self::Done
    }
}

/// Xiaomi items the owner has confirmed, valid for one app version only
/// (FR-8). Stored locally as JSON by the app.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct XiaomiConfirmations {
    #[serde(default)]
    app_version: String,
    #[serde(default)]
    confirmed: u16,
}

impl XiaomiConfirmations {
    /// Drops every confirmation when the app version changed. Returns
    /// whether anything was cleared (so the caller knows to persist).
    pub fn reconcile(&mut self, app_version: &str) -> bool {
        if self.app_version == app_version {
            return false;
        }
        let had_any = self.confirmed != 0;
        self.app_version.clear();
        self.app_version.push_str(app_version);
        self.confirmed = 0;
        had_any
    }

    /// Records a confirmation for the current version. Non-Xiaomi items are
    /// ignored.
    pub fn confirm(&mut self, item: SetupItem, app_version: &str) {
        if !item.is_xiaomi() {
            return;
        }
        self.reconcile(app_version);
        self.confirmed |= item.bit();
    }

    /// Takes a confirmation back (the owner toggled it off).
    pub fn unconfirm(&mut self, item: SetupItem) {
        self.confirmed &= !item.bit();
    }

    /// Whether `item` is confirmed for `app_version`.
    pub fn is_confirmed(&self, item: SetupItem, app_version: &str) -> bool {
        self.app_version == app_version && self.confirmed & item.bit() != 0
    }
}

/// Xiaomi pages opened in this session (not persisted).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct OpenedThisSession(u16);

impl OpenedThisSession {
    pub fn mark(&mut self, item: SetupItem) {
        self.0 |= item.bit();
    }

    pub fn contains(self, item: SetupItem) -> bool {
        self.0 & item.bit() != 0
    }
}

/// UX-Q3 gating: a Xiaomi item can be confirmed only after its settings page
/// was opened once in this session.
pub fn can_confirm(item: SetupItem, opened_this_session: OpenedThisSession) -> bool {
    item.is_xiaomi() && opened_this_session.contains(item)
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ItemStatus {
    pub item: SetupItem,
    pub state: ItemState,
}

/// "{done} of {total}" and whether everything is done.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Progress {
    pub done: usize,
    pub total: usize,
    pub complete: bool,
}

impl Progress {
    /// "3 of 5".
    pub fn label(&self) -> SmallText<16> {
        let mut out = SmallText::new();
        out.push_padded(self.done as u64, 1);
        out.push_str(" of ");
        out.push_padded(self.total as u64, 1);
        out
    }
}

/// The derived, ordered checklist. Fixed-size: building one never allocates.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SetupChecklist {
    items: [ItemStatus; SetupItem::ALL.len()],
    len: usize,
}

impl SetupChecklist {
    pub fn items(&self) -> &[ItemStatus] {
        &self.items[..self.len]
    }

    pub fn state_of(&self, item: SetupItem) -> Option<ItemState> {
        self.items()
            .iter()
            .find(|s| s.item == item)
            .map(|s| s.state)
    }

    pub fn progress(&self) -> Progress {
        let done = self.items().iter().filter(|s| s.state.is_done()).count();
        let total = self.len;
        Progress {
            done,
            total,
            complete: total > 0 && done == total,
        }
    }

    pub fn complete(&self) -> bool {
        self.progress().complete
    }

    /// `something_changed`: items that were done in `previous` and are not
    /// done now (a revoked permission, a lost role, a version reset). Their
    /// titles are available through [`SetupItem::title`].
    pub fn reopened_since(&self, previous: &SetupChecklist) -> Vec<SetupItem> {
        self.items()
            .iter()
            .filter(|now| {
                !now.state.is_done() && previous.state_of(now.item) == Some(ItemState::Done)
            })
            .map(|now| now.item)
            .collect()
    }
}

/// Banner text for reopened items; `None` when nothing reopened.
pub fn changed_title(reopened: &[SetupItem]) -> Option<String> {
    let (first, rest) = reopened.split_first()?;
    Some(if rest.is_empty() {
        format!("Something changed: {}", first.title())
    } else {
        format!(
            "Something changed: {} and {} more",
            first.title(),
            rest.len()
        )
    })
}

/// The reopened items that are still not done in `now`.
pub fn still_reopened(reopened: &[SetupItem], now: &SetupChecklist) -> Vec<SetupItem> {
    reopened
        .iter()
        .copied()
        .filter(|item| now.state_of(*item).is_some_and(|s| !s.is_done()))
        .collect()
}

/// Builds the checklist. Xiaomi items appear only when `xiaomi` is true and
/// are `Done` only when confirmed for exactly `app_version`; otherwise
/// `Unverifiable`.
pub fn derive(
    facts: SetupFacts,
    xiaomi: bool,
    confirmations: &XiaomiConfirmations,
    app_version: &str,
) -> SetupChecklist {
    let placeholder = ItemStatus {
        item: SetupItem::DefaultPhoneApp,
        state: ItemState::NotDone,
    };
    let mut items = [placeholder; SetupItem::ALL.len()];
    let mut len = 0;
    let confirmed = |item| confirmations.is_confirmed(item, app_version);
    for item in SetupItem::ALL {
        let state = match item {
            SetupItem::DefaultPhoneApp => bool_state(facts.role_held),
            SetupItem::PhoneAndCallLog => bool_state(facts.phone_and_call_log_granted),
            SetupItem::Contacts => bool_state(facts.contacts_granted),
            SetupItem::Notifications => bool_state(facts.notifications_granted),
            SetupItem::FullScreenNotifications => bool_state(facts.full_screen_allowed),
            _ if !xiaomi => continue,
            SetupItem::BatterySaver => {
                if facts.battery_exempt || confirmed(item) {
                    ItemState::Done
                } else {
                    ItemState::Unverifiable
                }
            }
            SetupItem::Autostart => reading_state(item, facts.autostart, confirmed(item)),
            SetupItem::ShowOnLockScreen => reading_state(item, facts.lock_screen, confirmed(item)),
            _ => reading_state(item, facts.background_popups, confirmed(item)),
        };
        items[len] = ItemStatus { item, state };
        len += 1;
    }
    SetupChecklist { items, len }
}

fn reading_state(item: SetupItem, reading: Reading, confirmed: bool) -> ItemState {
    match reading {
        Reading::Granted => ItemState::Done,
        Reading::Denied if confirmed && !item.reading_is_authoritative() => ItemState::Done,
        Reading::Denied => ItemState::NotDone,
        Reading::Unknown if confirmed => ItemState::Done,
        Reading::Unknown => ItemState::Unverifiable,
    }
}

fn bool_state(done: bool) -> ItemState {
    if done {
        ItemState::Done
    } else {
        ItemState::NotDone
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use SetupItem::*;

    const ALL_GRANTED: SetupFacts = SetupFacts {
        role_held: true,
        phone_and_call_log_granted: true,
        contacts_granted: true,
        notifications_granted: true,
        full_screen_allowed: true,
        battery_exempt: false,
        autostart: Reading::Unknown,
        lock_screen: Reading::Unknown,
        background_popups: Reading::Unknown,
    };

    #[test]
    fn changed_title_counts_items() {
        assert_eq!(changed_title(&[]), None);
        assert_eq!(
            changed_title(&[Contacts]).as_deref(),
            Some("Something changed: Contacts")
        );
        assert_eq!(
            changed_title(&[Contacts, Notifications, DefaultPhoneApp]).as_deref(),
            Some("Something changed: Contacts and 2 more")
        );
    }

    #[test]
    fn still_reopened_drops_fixed_items() {
        let facts = SetupFacts {
            contacts_granted: false,
            ..ALL_GRANTED
        };
        let now = derive(facts, false, &XiaomiConfirmations::default(), "1");
        assert_eq!(still_reopened(&[Contacts, Notifications], &now), [Contacts]);
        assert!(still_reopened(&[], &now).is_empty());
    }

    fn order(list: &SetupChecklist) -> Vec<SetupItem> {
        list.items().iter().map(|s| s.item).collect()
    }

    #[test]
    fn non_xiaomi_has_the_five_core_items_in_order() {
        let list = derive(ALL_GRANTED, false, &XiaomiConfirmations::default(), "0.1.0");
        assert_eq!(
            order(&list),
            [
                DefaultPhoneApp,
                PhoneAndCallLog,
                Contacts,
                Notifications,
                FullScreenNotifications
            ]
        );
        assert!(list.complete());
        assert_eq!(list.progress().label(), "5 of 5");
    }

    #[test]
    fn xiaomi_adds_four_unverifiable_items() {
        let list = derive(ALL_GRANTED, true, &XiaomiConfirmations::default(), "0.1.0");
        assert_eq!(order(&list).len(), 9);
        assert_eq!(
            &order(&list)[5..],
            [Autostart, BatterySaver, ShowOnLockScreen, BackgroundPopups]
        );
        for s in &list.items()[5..] {
            assert_eq!(s.state, ItemState::Unverifiable);
        }
        assert!(!list.complete());
        assert_eq!(list.progress().label(), "5 of 9");
    }

    #[test]
    fn core_item_states_follow_facts() {
        let facts = SetupFacts {
            role_held: false,
            phone_and_call_log_granted: true,
            contacts_granted: false,
            notifications_granted: true,
            full_screen_allowed: false,
            ..SetupFacts::default()
        };
        let list = derive(facts, false, &XiaomiConfirmations::default(), "1");
        assert_eq!(list.state_of(DefaultPhoneApp), Some(ItemState::NotDone));
        assert_eq!(list.state_of(PhoneAndCallLog), Some(ItemState::Done));
        assert_eq!(list.state_of(Contacts), Some(ItemState::NotDone));
        assert_eq!(list.state_of(Notifications), Some(ItemState::Done));
        assert_eq!(
            list.state_of(FullScreenNotifications),
            Some(ItemState::NotDone)
        );
        assert_eq!(list.state_of(Autostart), None);
        assert_eq!(list.progress().label(), "2 of 5");
        assert!(!list.complete());
    }

    #[test]
    fn nothing_granted_is_zero_of_five() {
        let list = derive(
            SetupFacts::default(),
            false,
            &XiaomiConfirmations::default(),
            "1",
        );
        assert_eq!(list.progress().label(), "0 of 5");
        assert!(!list.complete());
    }

    #[test]
    fn confirmed_xiaomi_items_count_as_done() {
        let mut c = XiaomiConfirmations::default();
        for item in [Autostart, BatterySaver, ShowOnLockScreen, BackgroundPopups] {
            c.confirm(item, "0.1.0");
        }
        let list = derive(ALL_GRANTED, true, &c, "0.1.0");
        assert!(list.complete());
        assert_eq!(list.progress().label(), "9 of 9");
        assert_eq!(list.state_of(Autostart), Some(ItemState::Done));
    }

    #[test]
    fn partially_confirmed() {
        let mut c = XiaomiConfirmations::default();
        c.confirm(Autostart, "0.1.0");
        let list = derive(ALL_GRANTED, true, &c, "0.1.0");
        assert_eq!(list.progress().label(), "6 of 9");
        assert_eq!(list.state_of(BatterySaver), Some(ItemState::Unverifiable));
    }

    #[test]
    fn version_change_makes_confirmations_unverifiable_again() {
        let mut c = XiaomiConfirmations::default();
        c.confirm(Autostart, "0.1.0");
        c.confirm(BatterySaver, "0.1.0");

        // derive alone already ignores stale confirmations...
        let list = derive(ALL_GRANTED, true, &c, "0.1.1");
        assert_eq!(list.state_of(Autostart), Some(ItemState::Unverifiable));

        // ...and reconcile clears them for persistence.
        assert!(c.reconcile("0.1.1"));
        assert!(!c.is_confirmed(Autostart, "0.1.0"));
        assert!(!c.is_confirmed(Autostart, "0.1.1"));
        assert!(!c.reconcile("0.1.1"), "second reconcile changes nothing");
    }

    #[test]
    fn reconcile_on_fresh_store_reports_nothing_cleared() {
        let mut c = XiaomiConfirmations::default();
        assert!(!c.reconcile("0.1.0"));
        c.confirm(Autostart, "0.1.0");
        assert!(c.is_confirmed(Autostart, "0.1.0"));
    }

    #[test]
    fn confirming_under_a_new_version_drops_old_ones() {
        let mut c = XiaomiConfirmations::default();
        c.confirm(Autostart, "0.1.0");
        c.confirm(BatterySaver, "0.2.0");
        assert!(!c.is_confirmed(Autostart, "0.2.0"));
        assert!(c.is_confirmed(BatterySaver, "0.2.0"));
    }

    #[test]
    fn unconfirm_and_non_xiaomi_confirm() {
        let mut c = XiaomiConfirmations::default();
        c.confirm(Autostart, "1");
        c.unconfirm(Autostart);
        assert!(!c.is_confirmed(Autostart, "1"));
        c.confirm(Contacts, "1");
        assert!(!c.is_confirmed(Contacts, "1"));
    }

    #[test]
    fn confirmations_round_trip_through_serde() {
        let mut c = XiaomiConfirmations::default();
        c.confirm(BackgroundPopups, "0.1.0");
        let json = serde_json::to_string(&c).expect("ser");
        let back: XiaomiConfirmations = serde_json::from_str(&json).expect("de");
        assert_eq!(back, c);
        let empty: XiaomiConfirmations = serde_json::from_str("{}").expect("de");
        assert_eq!(empty, XiaomiConfirmations::default());
    }

    #[test]
    fn can_confirm_requires_the_page_to_have_been_opened() {
        let mut opened = OpenedThisSession::default();
        assert!(!can_confirm(Autostart, opened));
        opened.mark(Autostart);
        assert!(can_confirm(Autostart, opened));
        assert!(!can_confirm(BatterySaver, opened), "per item");
        opened.mark(Contacts);
        assert!(!can_confirm(Contacts, opened), "never for core items");
    }

    #[test]
    fn regression_reports_reopened_items() {
        let mut c = XiaomiConfirmations::default();
        c.confirm(Autostart, "1");
        let before = derive(ALL_GRANTED, true, &c, "1");

        let mut facts = ALL_GRANTED;
        facts.contacts_granted = false;
        facts.full_screen_allowed = false;
        let after = derive(facts, true, &c, "1");

        let reopened = after.reopened_since(&before);
        assert_eq!(reopened, [Contacts, FullScreenNotifications]);
        let names: Vec<_> = reopened.iter().map(|i| i.title()).collect();
        assert_eq!(names, ["Contacts", "Full-screen notifications"]);
        assert!(!after.complete());
    }

    #[test]
    fn items_that_were_never_done_are_not_reported() {
        let before = derive(
            SetupFacts::default(),
            false,
            &XiaomiConfirmations::default(),
            "1",
        );
        let after = derive(
            SetupFacts::default(),
            false,
            &XiaomiConfirmations::default(),
            "1",
        );
        assert!(after.reopened_since(&before).is_empty());
    }

    #[test]
    fn improvement_reports_nothing() {
        let before = derive(
            SetupFacts::default(),
            false,
            &XiaomiConfirmations::default(),
            "1",
        );
        let after = derive(ALL_GRANTED, false, &XiaomiConfirmations::default(), "1");
        assert!(after.reopened_since(&before).is_empty());
    }

    #[test]
    fn version_reset_reopens_xiaomi_items() {
        let mut c = XiaomiConfirmations::default();
        c.confirm(Autostart, "1");
        let before = derive(ALL_GRANTED, true, &c, "1");
        let after = derive(ALL_GRANTED, true, &c, "2");
        assert_eq!(after.reopened_since(&before), [Autostart]);
    }

    #[test]
    fn xiaomi_flag_turned_off_has_no_xiaomi_rows_to_compare() {
        let mut c = XiaomiConfirmations::default();
        c.confirm(Autostart, "1");
        let before = derive(ALL_GRANTED, true, &c, "1");
        let after = derive(ALL_GRANTED, false, &c, "1");
        assert!(after.reopened_since(&before).is_empty());
    }

    #[test]
    fn readable_states_win_over_unknown() {
        let mut facts = ALL_GRANTED;
        facts.autostart = Reading::Granted;
        facts.battery_exempt = true;
        facts.lock_screen = Reading::Denied;
        let list = derive(facts, true, &XiaomiConfirmations::default(), "1");
        assert_eq!(list.state_of(Autostart), Some(ItemState::Done));
        assert_eq!(list.state_of(BatterySaver), Some(ItemState::Done));
        assert_eq!(list.state_of(ShowOnLockScreen), Some(ItemState::NotDone));
        assert_eq!(
            list.state_of(BackgroundPopups),
            Some(ItemState::Unverifiable)
        );
    }

    #[test]
    fn denied_autostart_beats_a_confirmation_but_other_ops_do_not() {
        let mut c = XiaomiConfirmations::default();
        c.confirm(Autostart, "1");
        c.confirm(ShowOnLockScreen, "1");
        let mut facts = ALL_GRANTED;
        facts.autostart = Reading::Denied;
        facts.lock_screen = Reading::Denied;
        let list = derive(facts, true, &c, "1");
        assert_eq!(list.state_of(Autostart), Some(ItemState::NotDone));
        assert_eq!(list.state_of(ShowOnLockScreen), Some(ItemState::Done));
    }

    #[test]
    fn keys_round_trip() {
        for item in SetupItem::ALL {
            assert_eq!(SetupItem::from_key(item.key()), Some(item));
        }
        assert_eq!(SetupItem::from_key("nope"), None);
        assert_eq!(Reading::from_option(Some(true)), Reading::Granted);
        assert_eq!(Reading::from_option(None), Reading::Unknown);
    }

    #[test]
    fn every_item_has_copy() {
        for item in SetupItem::ALL {
            assert!(!item.title().is_empty());
            assert!(!item.reason().is_empty());
        }
        assert_eq!(SetupItem::ALL.iter().filter(|i| i.is_xiaomi()).count(), 4);
    }

    #[test]
    fn item_bits_are_distinct() {
        let mut seen = 0u16;
        for item in SetupItem::ALL {
            assert_eq!(seen & item.bit(), 0);
            seen |= item.bit();
        }
    }
}

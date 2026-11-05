//! Stops settings. Every struct is `#[serde(default)]` so files written by
//! older versions keep loading.

use std::time::Duration;

use serde::{Deserialize, Serialize};

use crate::AlarmRule;
use crate::notice::DEFAULT_TEMPLATE;
use crate::session::NoticeState;

/// The "Wake me" choices.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub enum RuleChoice {
    Radius300,
    #[default]
    Radius500,
    Radius1k,
    Eta2min,
}

impl RuleChoice {
    pub const ALL: [RuleChoice; 4] = [
        Self::Radius300,
        Self::Radius500,
        Self::Radius1k,
        Self::Eta2min,
    ];

    pub fn to_rule(self) -> AlarmRule {
        match self {
            Self::Radius300 => AlarmRule::Radius { metres: 300.0 },
            Self::Radius500 => AlarmRule::Radius { metres: 500.0 },
            Self::Radius1k => AlarmRule::Radius { metres: 1_000.0 },
            Self::Eta2min => AlarmRule::Eta {
                before: Duration::from_secs(120),
                min_metres: 300.0,
            },
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Self::Radius300 => "300 m",
            Self::Radius500 => "500 m",
            Self::Radius1k => "1 km",
            Self::Eta2min => "2 min",
        }
    }
}

/// When the "arriving soon" message goes out.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub enum NoticeWhen {
    /// Together with the alarm.
    #[default]
    WithAlarm,
    /// This many minutes before arrival.
    MinutesBefore(u8),
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct NoticePrefs {
    /// WhatsApp chat id on the gateway.
    pub chat: String,
    /// Chat name for display.
    pub chat_name: String,
    pub template: String,
    pub when: NoticeWhen,
}

impl Default for NoticePrefs {
    fn default() -> Self {
        Self {
            chat: String::new(),
            chat_name: String::new(),
            template: DEFAULT_TEMPLATE.to_owned(),
            when: NoticeWhen::default(),
        }
    }
}

impl NoticePrefs {
    /// The notice for a new trip whose own alarm is `alarm`. "With the
    /// alarm" copies that rule; "N minutes before" is an ETA rule of its own,
    /// which falls back to a distance when the speed is unusable.
    pub fn to_notice_state(&self, alarm: AlarmRule) -> NoticeState {
        let rule = match self.when {
            NoticeWhen::WithAlarm => alarm,
            NoticeWhen::MinutesBefore(m) => AlarmRule::Eta {
                before: Duration::from_secs(u64::from(m.max(1)) * 60),
                min_metres: 1_000.0,
            },
        };
        NoticeState {
            chat: self.chat.clone(),
            text_template: self.template.clone(),
            rule,
            sent: false,
            attempts: 0,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct StopsPrefs {
    pub rule: RuleChoice,
    pub sound: bool,
    pub vibrate: bool,
    pub snooze_min: u8,
    /// `None`: the arriving-soon message is off.
    pub notice: Option<NoticePrefs>,
}

impl Default for StopsPrefs {
    fn default() -> Self {
        Self {
            rule: RuleChoice::default(),
            sound: true,
            vibrate: true,
            snooze_min: 5,
            notice: None,
        }
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn defaults() {
        let p = StopsPrefs::default();
        assert_eq!(p.rule, RuleChoice::Radius500);
        assert!(p.sound && p.vibrate);
        assert_eq!(p.snooze_min, 5);
        assert!(p.notice.is_none());
        assert_eq!(p.rule.to_rule(), AlarmRule::Radius { metres: 500.0 });
    }

    #[test]
    fn empty_json_loads_defaults() {
        let p: StopsPrefs = serde_json::from_str("{}").unwrap();
        assert_eq!(p, StopsPrefs::default());
        let n: NoticePrefs = serde_json::from_str("{}").unwrap();
        assert_eq!(n.template, DEFAULT_TEMPLATE);
    }

    #[test]
    fn partial_json_keeps_the_rest() {
        let p: StopsPrefs =
            serde_json::from_str(r#"{"rule":"Radius1k","vibrate":false,"future":1}"#).unwrap();
        assert_eq!(p.rule, RuleChoice::Radius1k);
        assert!(!p.vibrate && p.sound);
    }

    #[test]
    fn round_trip() {
        let p = StopsPrefs {
            rule: RuleChoice::Eta2min,
            notice: Some(NoticePrefs {
                chat: "123@g.us".into(),
                chat_name: "Family".into(),
                template: "On my way to {stop}".into(),
                when: NoticeWhen::MinutesBefore(10),
            }),
            ..StopsPrefs::default()
        };
        let back: StopsPrefs = serde_json::from_str(&serde_json::to_string(&p).unwrap()).unwrap();
        assert_eq!(back, p);
    }

    #[test]
    fn rule_choices_map() {
        assert_eq!(
            RuleChoice::Radius300.to_rule(),
            AlarmRule::Radius { metres: 300.0 }
        );
        assert_eq!(
            RuleChoice::Radius1k.to_rule(),
            AlarmRule::Radius { metres: 1_000.0 }
        );
        assert!(matches!(
            RuleChoice::Eta2min.to_rule(),
            AlarmRule::Eta { .. }
        ));
        assert_eq!(RuleChoice::ALL.len(), 4);
    }

    #[test]
    fn notice_state_from_prefs() {
        let alarm = AlarmRule::Radius { metres: 500.0 };
        let mut n = NoticePrefs {
            chat: "c".into(),
            ..NoticePrefs::default()
        };
        let s = n.to_notice_state(alarm);
        assert_eq!(s.rule, alarm);
        assert_eq!((s.chat.as_str(), s.sent, s.attempts), ("c", false, 0));
        n.when = NoticeWhen::MinutesBefore(10);
        match n.to_notice_state(alarm).rule {
            AlarmRule::Eta { before, .. } => assert_eq!(before, Duration::from_secs(600)),
            other => panic!("{other:?}"),
        }
    }
}

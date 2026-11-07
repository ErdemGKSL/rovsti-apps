//! The rich fixture behind the screenshot tests (`docs/screenshots.md`): 24
//! conversations that reproduce what a real inbox looks like. Test-only.
//!
//! * 15 SMS threads: contacts with long Turkish names, an OTP, three spam
//!   senders, a group, and an unread run of three;
//! * 9 WhatsApp chats: one merged with an SMS person, four groups, unread
//!   badges of 1, 2, 12 and 120;
//! * three people with status updates.

use std::sync::atomic::{AtomicBool, Ordering};

use rovsti_core::{
    Message, MessageDirection, PhoneNumber, ThreadId,
    inbox::InboxThread,
    recents::LocaleTime,
    sms::{DeliveryState, SmsBox},
    wire::{WireContact, WireNumber},
};
use rovsti_wa_proto::{StatusAuthor, StatusItem, StatusKind, StatusList};

use super::{DAY, HOUR, MINUTE, Preview};

static ON: AtomicBool = AtomicBool::new(false);

/// Switches the desktop preview to the rich fixture (process-global; the
/// shots test calls it once, before the backend starts).
#[cfg(feature = "shots")]
pub fn use_shots_fixture(on: bool) {
    ON.store(on, Ordering::Relaxed);
}

/// Istanbul time (UTC+3), 24-hour clock: the shots' phone locale.
pub fn locale() -> LocaleTime {
    LocaleTime {
        tz_offset_s: 3 * 3600,
        ..LocaleTime::default()
    }
}

pub(crate) fn enabled() -> bool {
    ON.load(Ordering::Relaxed)
}

pub const CAN: &str = "+905361112233";
pub const PROF: &str = "+905333334455";
pub const IPEK: &str = "+905422223344";
pub const CAGLAR: &str = "+905377778899";
pub const ANNE: &str = "+905301112200";
pub const DENTIST: &str = "+905355556677";
pub const BULENT: &str = "+905388889900";
pub const ZEYNEP: &str = "+905399990011";
pub const GULSEN: &str = "+905311110022";

/// Group chats.
pub const GROUP_NEWS: &str = "120363041111111111@g.us";
pub const GROUP_FAMILY: &str = "120363042222222222@g.us";
pub const GROUP_PITCH: &str = "120363043333333333@g.us";
pub const GROUP_HR: &str = "120363044444444444@g.us";

pub const NEWS_TITLE: &str = "Maltepe | Yazılım Mühendisliği Duyuruları";
pub const FAMILY_TITLE: &str = "Aile Grubu (Çınar & Yıldız)";
pub const PITCH_TITLE: &str = "Halı Saha Cumartesi 20:00 – Kadıköy";
pub const HR_TITLE: &str = "Şirket İK – Yeni Yıl Planlaması";

/// `905321234567@s.whatsapp.net` for `+905321234567`.
pub fn user_jid(number: &str) -> String {
    format!("{}@s.whatsapp.net", number.trim_start_matches('+'))
}

/// One incoming (`false`) or outgoing (`true`) message of a thread.
struct Msg {
    body: &'static str,
    ago: i64,
    out: bool,
    read: bool,
}

const fn inc(body: &'static str, ago: i64) -> Msg {
    Msg {
        body,
        ago,
        out: false,
        read: true,
    }
}

const fn unread(body: &'static str, ago: i64) -> Msg {
    Msg {
        body,
        ago,
        out: false,
        read: false,
    }
}

const fn out(body: &'static str, ago: i64) -> Msg {
    Msg {
        body,
        ago,
        out: true,
        read: true,
    }
}

const LONG: &str = "Bu hafta sonu için küçük bir planım var: cumartesi sabah erkenden Şile'ye \
çıkıp yürüyüş yaparız, öğle yemeğini Ağva'da yeriz, dönüşte de Çamlıca'dan geçip çay içeriz. \
Arabayı ben alırım, benzin masrafını da yarı yarıya bölüşürüz. Ayrıca İpek ile Çağlar da gelmek \
istiyor, dört kişi olursak çok daha eğlenceli olur. Ne dersin, uygun musun?";

impl Preview {
    /// The 15 SMS threads of the shots fixture, relative to `now_ms`.
    pub fn shots(now_ms: i64) -> Self {
        let mut p = Self {
            rows: Vec::new(),
            addresses: Vec::new(),
            next_row: 1,
            next_thread: 16,
        };
        let at = |ago: i64| now_ms - ago;
        let mut thread = |id: i64, addresses: &[&str], msgs: &[Msg]| {
            p.addresses
                .push((id, addresses.iter().map(|a| (*a).to_owned()).collect()));
            for m in msgs {
                let box_ = if m.out { SmsBox::Sent } else { SmsBox::Inbox };
                p.push(id, addresses[0], m.body, at(m.ago), box_, m.read);
            }
        };
        // 1: Ayse. A few days of mixed bubbles, a long one, an OTP, two unread.
        thread(
            1,
            &[super::AYSE],
            &[
                inc(
                    "Selam, cumartesi için bir fikrin var mı?",
                    3 * DAY + 2 * HOUR,
                ),
                out("Henüz yok, sen söyle :)", 3 * DAY + 2 * HOUR - 4 * MINUTE),
                inc(
                    "Bu arada kargo için doğrulama kodu: 482913",
                    2 * DAY + 5 * HOUR,
                ),
                out("Teşekkürler, girdim", 2 * DAY + 5 * HOUR - 2 * MINUTE),
                inc("Yarın akşam yemeğe gelir misin?", DAY + 6 * HOUR),
                out("Tabii, ne getireyim?", DAY + 5 * HOUR),
                inc(LONG, DAY + 2 * HOUR),
                out("Süper olur, bayıldım!", DAY + HOUR),
                out("Saat kaçta çıkıyoruz?", DAY + 59 * MINUTE),
                inc("07:30 gibi seni alırım", DAY + 20 * MINUTE),
                unread("Hâlâ 7'de buluşuyor muyuz?", 9 * MINUTE),
                unread("Pastayı ben getiririm", 5 * MINUTE),
            ],
        );
        // 2: the OTP row.
        thread(
            2,
            &["GARANTI"],
            &[unread(
                "Doğrulama kodunuz 731924. Bu kodu kimseyle paylaşmayın. Garanti BBVA",
                12 * MINUTE,
            )],
        );
        // 3, 7, 8: spam from unknown senders with links.
        thread(
            3,
            &["+447700900123"],
            &[unread(
                "You won a prize! Claim it now: http://bit.ly/claim-now",
                2 * HOUR + 30 * MINUTE,
            )],
        );
        thread(
            7,
            &["+905550001111"],
            &[unread(
                "Kredi kartınıza özel %0 faiz fırsatı! Hemen başvurun: http://kredi-firsati.co/x",
                5 * HOUR,
            )],
        );
        thread(
            8,
            &["BETPARK"],
            &[unread(
                "Hoş geldin bonusu 500 TL seni bekliyor: bit.ly/bonus500",
                9 * HOUR,
            )],
        );
        // 4: an SMS group.
        thread(
            4,
            &[super::MERT, super::ELIF],
            &[
                inc("Kim içecek getiriyor?", 5 * DAY + 3 * HOUR),
                inc("Ben getiririm", 5 * DAY + 2 * HOUR),
            ],
        );
        // 5: Mert alone.
        thread(
            5,
            &[super::MERT],
            &[
                out("Bugün toplantı kaçta?", 6 * HOUR + 10 * MINUTE),
                inc("Tamam, yarın görüşürüz", 6 * HOUR),
            ],
        );
        // 6: three unread in a row.
        thread(
            6,
            &[CAN],
            &[
                inc("Abi müsait misin?", 90 * MINUTE),
                unread("Servisten aradılar, araba hazırmış", 50 * MINUTE),
                unread("Fatura da 4.850 TL tutmuş", 45 * MINUTE),
                unread("Akşam uğrayıp alalım mı?", 41 * MINUTE),
            ],
        );
        // 9: a sender that is not a person.
        thread(
            9,
            &["TURKCELL"],
            &[inc(
                "Sayın müşterimiz, Kasım ayı faturanız 389,90 TL'dir. Son ödeme tarihi 18 Kasım.",
                DAY + 9 * HOUR,
            )],
        );
        // 10: a very long contact name and a long snippet.
        thread(
            10,
            &[PROF],
            &[
                out(
                    "Hocam bitirme projesi sunumu için uygun bir saat var mı?",
                    8 * HOUR,
                ),
                inc(
                    "Perşembe günü 14:00'te ofisimde görüşebiliriz; raporun son halini ve \
                     kaynakçayı da yanınızda getirmeyi unutmayın lütfen.",
                    7 * HOUR,
                ),
            ],
        );
        thread(
            11,
            &[IPEK],
            &[
                out("Konser biletlerini aldım!", 2 * DAY + HOUR),
                inc("Harikasın, çok teşekkürler", 2 * DAY),
            ],
        );
        thread(
            12,
            &[CAGLAR],
            &[inc(
                "Toplantı notlarını gönderdim, bir göz atar mısın?",
                2 * DAY + 30 * MINUTE,
            )],
        );
        thread(
            13,
            &["YURTICI"],
            &[unread(
                "Gönderiniz bugün dağıtıma çıktı. Takip no: 4052 8817 3301",
                3 * DAY,
            )],
        );
        thread(
            14,
            &[ANNE],
            &[
                inc("Kızım yemek yedin mi, akşama çorba yaptım", 12 * HOUR),
                out("Yedim anneciğim, sen merak etme", 11 * HOUR + 40 * MINUTE),
            ],
        );
        thread(
            15,
            &[DENTIST],
            &[inc(
                "Sayın Ağaoğlu, 12 Kasım Perşembe saat 10:30'daki randevunuzu hatırlatırız.",
                2 * DAY + 4 * HOUR,
            )],
        );
        // Sent texts show their ticks.
        for row in p.rows.iter_mut().filter(|r| r.box_.is_outgoing()) {
            row.delivery = DeliveryState::Delivered;
        }
        p
    }

    /// The address book of the shots fixture.
    pub fn shots_contacts() -> Vec<WireContact> {
        let contact = |id: i64, name: &str, number: &str| WireContact {
            id,
            display_name: name.to_owned(),
            numbers: vec![WireNumber {
                raw: number.to_owned(),
                label: Some("Mobile".to_owned()),
                default: true,
            }],
            ..WireContact::default()
        };
        vec![
            contact(1, "Ayşe Yılmaz", super::AYSE),
            contact(2, "Mert Demir", super::MERT),
            contact(3, "Elif Kaya", super::ELIF),
            contact(4, "Can Öztürk", CAN),
            contact(5, "Prof. Dr. Hüseyin Gökhan Şahin-Çelikoğlu", PROF),
            contact(6, "İpek Güneş", IPEK),
            contact(7, "Çağlar Ünlü", CAGLAR),
            contact(8, "Anneciğim", ANNE),
            contact(9, "Dr. Şükrü Ağaoğlu (Diş Kliniği)", DENTIST),
            contact(10, "Bülent Öğretmen", BULENT),
            contact(11, "Zeynep Çakır", ZEYNEP),
            contact(12, "Gülşen Teyze", GULSEN),
        ]
    }
}

/// One WhatsApp chat: `(jid, sender, title)`, then its newest text, how long
/// ago it came, and the unread count.
fn wa_thread(
    (jid, sender, title): (String, Option<&str>, &str),
    (body, ago, unread): (&str, i64, u32),
    is_group: bool,
    now_ms: i64,
) -> InboxThread {
    let id = ThreadId::WhatsApp(jid);
    InboxThread {
        last: Message {
            thread: id.clone(),
            direction: MessageDirection::Incoming,
            sender: sender.and_then(|s| PhoneNumber::parse(s).ok()),
            body: body.to_owned(),
            timestamp_ms: now_ms - ago,
            read: unread == 0,
        },
        id,
        unread,
        title: Some(title.to_owned()),
        is_group,
    }
}

/// The nine WhatsApp chats of the shots fixture. The first is Ayse's, older
/// than her newest SMS so the merged row opens the SMS thread.
pub fn wa_threads(now_ms: i64) -> Vec<InboxThread> {
    let chat = |number: &str, title: &str, body: &str, ago, unread| {
        wa_thread(
            (user_jid(number), Some(number), title),
            (body, ago, unread),
            false,
            now_ms,
        )
    };
    let group = |jid: &str, sender: &str, title: &str, body: &str, ago, unread| {
        wa_thread(
            (jid.to_owned(), Some(sender), title),
            (body, ago, unread),
            true,
            now_ms,
        )
    };
    vec![
        chat(
            super::AYSE,
            "Ayşe",
            "Hafta sonundan fotoğraflar",
            3 * HOUR,
            1,
        ),
        group(
            GROUP_NEWS,
            BULENT,
            NEWS_TITLE,
            "Bülent: Bitirme projesi teslim tarihi 20 Aralık'a uzatıldı, ayrıntılar duyuru \
             sayfasında",
            25 * MINUTE,
            120,
        ),
        chat(
            super::ELIF,
            "Elif Kaya",
            "Akşam sinemaya gidelim mi?",
            70 * MINUTE,
            1,
        ),
        group(
            GROUP_FAMILY,
            ANNE,
            FAMILY_TITLE,
            "Anneciğim: Pazar kahvaltısına herkes gelsin, börek yapıyorum",
            2 * HOUR,
            12,
        ),
        group(
            GROUP_PITCH,
            CAN,
            PITCH_TITLE,
            "Can: Kaleci arıyoruz, gelebilecek biri var mı?",
            4 * HOUR,
            0,
        ),
        chat(
            ZEYNEP,
            "Zeynep Çakır",
            "Toplantı linkini atar mısın lütfen?",
            8 * HOUR,
            2,
        ),
        group(
            GROUP_HR,
            IPEK,
            HR_TITLE,
            "İpek: Yıl sonu izin takvimini ortak tabloya ekledim, lütfen kontrol edin",
            DAY + 2 * HOUR,
            0,
        ),
        chat(
            BULENT,
            "Bülent Öğretmen",
            "Ödev teslimi için son gün Cuma, geç gelen çalışmalar kabul edilmeyecektir.",
            DAY + 5 * HOUR,
            0,
        ),
        chat(
            GULSEN,
            "Gülşen Teyze",
            "Allah razı olsun yavrum",
            4 * DAY,
            0,
        ),
    ]
}

/// Status updates from three people (Ayse, Elif and Bulent), newest first.
pub fn statuses(now_ms: i64) -> StatusList {
    let item =
        |id: &str, author: &str, name: &str, ago: i64, text: &str, viewed: bool| StatusItem {
            id: id.to_owned(),
            author: user_jid(author),
            timestamp_ms: now_ms - ago,
            expires_ms: now_ms - ago + rovsti_wa_proto::status::STATUS_TTL_MS,
            kind: StatusKind::Text,
            text: Some(text.to_owned()),
            background_argb: Some(0xFF1E_6E4F),
            author_name: Some(name.to_owned()),
            viewed,
            ..StatusItem::default()
        };
    let author = |number: &str, name: &str, items: Vec<StatusItem>| StatusAuthor {
        author: user_jid(number),
        name: Some(name.to_owned()),
        items,
    };
    StatusList {
        mine: Vec::new(),
        authors: vec![
            author(
                super::AYSE,
                "Ayşe Yılmaz",
                vec![
                    item(
                        "s1",
                        super::AYSE,
                        "Ayşe Yılmaz",
                        3 * HOUR,
                        "Şile'de gün batımı",
                        false,
                    ),
                    item("s2", super::AYSE, "Ayşe Yılmaz", HOUR, "Çay vakti", false),
                ],
            ),
            author(
                super::ELIF,
                "Elif Kaya",
                vec![item(
                    "s3",
                    super::ELIF,
                    "Elif Kaya",
                    5 * HOUR,
                    "Final haftası, dua edin",
                    false,
                )],
            ),
            author(
                BULENT,
                "Bülent Öğretmen",
                vec![item(
                    "s4",
                    BULENT,
                    "Bülent Öğretmen",
                    20 * HOUR,
                    "Yeni dönem başlıyor",
                    true,
                )],
            ),
        ],
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOW: i64 = 1_794_055_200_000;

    fn sms_unread(preview: &Preview, id: i64) -> u32 {
        preview
            .threads()
            .threads
            .iter()
            .find(|t| t.thread_id == id)
            .map_or(0, |t| t.unread)
    }

    #[test]
    fn the_fixture_has_24_threads() {
        let sms = Preview::shots(NOW).threads().threads.len();
        assert_eq!(sms, 15);
        assert_eq!(sms + wa_threads(NOW).len(), 24);
    }

    #[test]
    fn unread_badges_cover_1_3_12_and_120() {
        let preview = Preview::shots(NOW);
        let wa = wa_threads(NOW);
        let badge = |jid: &str| {
            wa.iter()
                .find(|t| t.id == ThreadId::WhatsApp(jid.into()))
                .map(|t| t.unread)
        };
        assert_eq!(sms_unread(&preview, 6), 3);
        assert_eq!(badge(GROUP_FAMILY), Some(12));
        assert_eq!(badge(GROUP_NEWS), Some(120));
        assert_eq!(badge(&user_jid(super::super::ELIF)), Some(1));
    }

    #[test]
    fn there_are_four_wa_groups_and_every_thread_is_older_than_now() {
        let wa = wa_threads(NOW);
        assert_eq!(wa.iter().filter(|t| t.is_group).count(), 4);
        assert!(wa.iter().all(|t| t.last.timestamp_ms < NOW));
        let sms = Preview::shots(NOW).threads().threads;
        assert!(sms.iter().all(|t| t.date_ms < NOW));
    }

    #[test]
    fn three_spam_rows_have_links_and_an_otp_has_a_code() {
        let preview = Preview::shots(NOW);
        let threads = preview.threads().threads;
        let body = |id: i64| {
            threads
                .iter()
                .find(|t| t.thread_id == id)
                .expect("thread")
                .snippet
                .clone()
        };
        for id in [3, 7, 8] {
            assert!(rovsti_core::spam::contains_link(&body(id)), "{id}");
        }
        assert!(rovsti_core::otp::detect(&body(2)).is_some());
    }

    #[test]
    fn three_people_have_status_updates() {
        let list = statuses(NOW);
        assert_eq!(list.authors.len(), 3);
        assert!(list.unviewed() >= 3);
        assert!(
            list.authors
                .iter()
                .flat_map(|a| &a.items)
                .all(|i| !i.is_expired(NOW))
        );
    }

    #[test]
    fn the_phone_clock_is_istanbul() {
        assert_eq!(locale().tz_offset_s, 10_800);
        assert!(!locale().format.hour12);
    }

    #[test]
    fn contact_names_are_long_and_turkish() {
        let contacts = Preview::shots_contacts();
        assert!(contacts.iter().any(|c| c.display_name.chars().count() > 30));
        assert!(contacts.iter().any(|c| c.display_name.contains('İ')));
    }
}

//! A 20-line logger: logcat on Android (through `liblog`, no extra crate), stderr elsewhere.

use log::{Level, LevelFilter, Log, Metadata, Record};

struct Logger;

static LOGGER: Logger = Logger;

impl Log for Logger {
    fn enabled(&self, metadata: &Metadata) -> bool {
        metadata.level() <= Level::Debug
    }

    fn log(&self, record: &Record) {
        if self.enabled(record.metadata()) {
            write(record.level(), &format!("{}", record.args()));
        }
    }

    fn flush(&self) {}
}

#[cfg(target_os = "android")]
fn write(level: Level, message: &str) {
    use std::ffi::{CString, c_char, c_int};
    unsafe extern "C" {
        fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
    }
    let priority = match level {
        Level::Error => 6,
        Level::Warn => 5,
        Level::Info => 4,
        Level::Debug => 3,
        Level::Trace => 2,
    };
    let text = CString::new(message.replace('\0', " ")).unwrap_or_default();
    // SAFETY: both pointers are valid NUL-terminated strings for the duration of the call.
    unsafe { __android_log_write(priority, c"rovsti".as_ptr(), text.as_ptr()) };
}

#[cfg(not(target_os = "android"))]
fn write(level: Level, message: &str) {
    eprintln!("[rovsti {level}] {message}");
}

/// Installs the logger once; a second call (or another logger already set) is a no-op.
pub fn init() {
    if log::set_logger(&LOGGER).is_ok() {
        log::set_max_level(LevelFilter::Debug);
    }
}

//! Gateway configuration: an optional TOML file with `WA_GATEWAY_*` overrides.

use std::net::SocketAddr;
use std::path::{Path, PathBuf};

pub const DEFAULT_BIND: &str = "127.0.0.1:8787";
pub const DEFAULT_MAX_INSTANCES: u32 = 4;
/// Largest file `POST /v1/messages/media` accepts (WhatsApp itself stops at 100 MB).
pub const DEFAULT_MAX_MEDIA_BYTES: usize = 64 * 1024 * 1024;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FcmConfig {
    pub project_id: String,
    /// Path of the Google service-account JSON.
    pub service_account: PathBuf,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Config {
    pub bind: SocketAddr,
    pub data_dir: PathBuf,
    pub max_instances: u32,
    pub fcm: Option<FcmConfig>,
    /// Believe `X-Forwarded-For`. Only set this behind a reverse proxy that
    /// overwrites the header.
    pub trust_proxy: bool,
    /// Request body limit of the media upload route.
    pub max_media_bytes: usize,
}

#[derive(Debug, thiserror::Error)]
pub enum ConfigError {
    #[error("cannot read {path}: {source}")]
    Read {
        path: PathBuf,
        source: std::io::Error,
    },
    #[error("config file: {0}")]
    Toml(#[from] toml::de::Error),
    #[error("{name}: {message}")]
    Invalid { name: &'static str, message: String },
}

fn invalid(name: &'static str, message: impl Into<String>) -> ConfigError {
    ConfigError::Invalid {
        name,
        message: message.into(),
    }
}

#[derive(Debug, Default, serde::Deserialize)]
#[serde(deny_unknown_fields)]
struct FileConfig {
    bind: Option<String>,
    data_dir: Option<PathBuf>,
    max_instances: Option<u32>,
    fcm_project_id: Option<String>,
    fcm_service_account: Option<PathBuf>,
    trust_proxy: Option<bool>,
}

fn parse_bool(name: &'static str, value: &str) -> Result<bool, ConfigError> {
    match value.trim().to_ascii_lowercase().as_str() {
        "1" | "true" | "yes" | "on" => Ok(true),
        "0" | "false" | "no" | "off" => Ok(false),
        other => Err(invalid(
            name,
            format!("expected true or false, got {other:?}"),
        )),
    }
}

impl Config {
    /// Pure: `file` is the TOML text, `env` looks up an environment variable.
    /// Environment values win over the file; empty ones count as unset.
    pub fn from_parts(
        file: Option<&str>,
        env: &dyn Fn(&str) -> Option<String>,
    ) -> Result<Self, ConfigError> {
        let file: FileConfig = match file {
            Some(text) => toml::from_str(text)?,
            None => FileConfig::default(),
        };
        let env = |name: &str| env(name).filter(|v| !v.trim().is_empty());

        let bind = env("WA_GATEWAY_BIND")
            .or(file.bind)
            .unwrap_or_else(|| DEFAULT_BIND.to_owned());
        let bind: SocketAddr = bind
            .trim()
            .parse()
            .map_err(|e| invalid("bind", format!("{bind:?}: {e}")))?;

        let data_dir = env("WA_GATEWAY_DATA")
            .map(PathBuf::from)
            .or(file.data_dir)
            .unwrap_or_else(|| PathBuf::from("wa-gateway-data"));

        let max_instances = match env("WA_GATEWAY_MAX_INSTANCES") {
            Some(v) => v
                .trim()
                .parse::<u32>()
                .map_err(|e| invalid("max_instances", format!("{v:?}: {e}")))?,
            None => file.max_instances.unwrap_or(DEFAULT_MAX_INSTANCES),
        };

        let trust_proxy = match env("WA_GATEWAY_TRUST_PROXY") {
            Some(v) => parse_bool("trust_proxy", &v)?,
            None => file.trust_proxy.unwrap_or(false),
        };

        let project = env("WA_GATEWAY_FCM_PROJECT").or(file.fcm_project_id);
        let account = env("WA_GATEWAY_FCM_SA")
            .map(PathBuf::from)
            .or(file.fcm_service_account);
        let fcm = match (project, account) {
            (Some(project_id), Some(service_account)) => Some(FcmConfig {
                project_id,
                service_account,
            }),
            (None, None) => None,
            _ => {
                return Err(invalid(
                    "fcm",
                    "set both the FCM project and the service account, or neither",
                ));
            }
        };

        Ok(Self {
            bind,
            data_dir,
            max_instances,
            fcm,
            trust_proxy,
            max_media_bytes: DEFAULT_MAX_MEDIA_BYTES,
        })
    }

    /// Reads `path` (when given) and the process environment.
    pub fn load(path: Option<&Path>) -> Result<Self, ConfigError> {
        let text = path
            .map(|p| {
                std::fs::read_to_string(p).map_err(|source| ConfigError::Read {
                    path: p.to_owned(),
                    source,
                })
            })
            .transpose()?;
        Self::from_parts(text.as_deref(), &|name| std::env::var(name).ok())
    }

    pub fn db_path(&self) -> PathBuf {
        self.data_dir.join("gateway.db")
    }

    /// Where the decrypted media of an instance is cached.
    pub fn media_dir(&self, instance: &str) -> PathBuf {
        self.data_dir.join("instances").join(instance).join("media")
    }

    /// Where the profile pictures an instance downloaded are cached.
    pub fn pictures_dir(&self, instance: &str) -> PathBuf {
        self.data_dir
            .join("instances")
            .join(instance)
            .join("pictures")
    }
}

/// What the command line asked for.
#[derive(Debug, PartialEq, Eq)]
pub enum Cli {
    Run { config: Option<PathBuf> },
    Help,
}

pub const USAGE: &str = "\
wa_gateway: WhatsApp gateway for the Rovsti apps

USAGE:
    wa_gateway [--config <file.toml>]

OPTIONS:
    --config <file>   TOML configuration file
    -h, --help        Print this help

CONFIG KEYS (file) / ENVIRONMENT OVERRIDES:
    bind                 WA_GATEWAY_BIND            default 127.0.0.1:8787
    data_dir             WA_GATEWAY_DATA            default ./wa-gateway-data
    max_instances        WA_GATEWAY_MAX_INSTANCES   default 4
    fcm_project_id       WA_GATEWAY_FCM_PROJECT     push is off unless both FCM keys are set
    fcm_service_account  WA_GATEWAY_FCM_SA
    trust_proxy          WA_GATEWAY_TRUST_PROXY     believe X-Forwarded-For (default false)

The gateway speaks plain HTTP; put a TLS reverse proxy in front of it.
";

impl Cli {
    pub fn parse<I: IntoIterator<Item = String>>(args: I) -> Result<Self, String> {
        let mut config = None;
        let mut args = args.into_iter();
        while let Some(arg) = args.next() {
            match arg.as_str() {
                "-h" | "--help" => return Ok(Self::Help),
                "--config" => {
                    let path = args.next().ok_or("--config needs a file")?;
                    config = Some(PathBuf::from(path));
                }
                other => match other.strip_prefix("--config=") {
                    Some(path) if !path.is_empty() => config = Some(PathBuf::from(path)),
                    _ => return Err(format!("unknown argument {other:?}")),
                },
            }
        }
        Ok(Self::Run { config })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    fn env_of<'a>(pairs: &'a [(&'a str, &'a str)]) -> impl Fn(&str) -> Option<String> + 'a {
        let map: HashMap<&str, &str> = pairs.iter().copied().collect();
        move |k| map.get(k).map(|v| (*v).to_owned())
    }

    #[test]
    fn defaults() {
        let c = Config::from_parts(None, &env_of(&[])).unwrap();
        assert_eq!(c.bind, "127.0.0.1:8787".parse().unwrap());
        assert_eq!(c.max_instances, 4);
        assert_eq!(c.fcm, None);
        assert!(!c.trust_proxy);
        assert_eq!(c.max_media_bytes, 64 * 1024 * 1024);
        assert_eq!(
            c.media_dir("abc"),
            PathBuf::from("wa-gateway-data/instances/abc/media")
        );
        assert_eq!(c.db_path(), PathBuf::from("wa-gateway-data/gateway.db"));
    }

    #[test]
    fn file_values() {
        let toml = r#"
            bind = "0.0.0.0:9000"
            data_dir = "/srv/wa"
            max_instances = 2
            trust_proxy = true
            fcm_project_id = "proj"
            fcm_service_account = "/etc/sa.json"
        "#;
        let c = Config::from_parts(Some(toml), &env_of(&[])).unwrap();
        assert_eq!(c.bind.port(), 9000);
        assert_eq!(c.data_dir, PathBuf::from("/srv/wa"));
        assert_eq!(c.max_instances, 2);
        assert!(c.trust_proxy);
        assert_eq!(
            c.fcm,
            Some(FcmConfig {
                project_id: "proj".into(),
                service_account: "/etc/sa.json".into()
            })
        );
    }

    #[test]
    fn env_overrides_file() {
        let toml = "bind = \"0.0.0.0:9000\"\nmax_instances = 2\ndata_dir = \"/a\"";
        let env = env_of(&[
            ("WA_GATEWAY_BIND", "127.0.0.1:1234"),
            ("WA_GATEWAY_DATA", "/b"),
            ("WA_GATEWAY_MAX_INSTANCES", "7"),
            ("WA_GATEWAY_TRUST_PROXY", "yes"),
            ("WA_GATEWAY_FCM_PROJECT", "p"),
            ("WA_GATEWAY_FCM_SA", "/sa.json"),
        ]);
        let c = Config::from_parts(Some(toml), &env).unwrap();
        assert_eq!(c.bind.port(), 1234);
        assert_eq!(c.data_dir, PathBuf::from("/b"));
        assert_eq!(c.max_instances, 7);
        assert!(c.trust_proxy);
        assert_eq!(c.fcm.unwrap().project_id, "p");
    }

    #[test]
    fn empty_env_counts_as_unset() {
        let toml = "max_instances = 3";
        let env = env_of(&[("WA_GATEWAY_MAX_INSTANCES", "  ")]);
        assert_eq!(
            Config::from_parts(Some(toml), &env).unwrap().max_instances,
            3
        );
    }

    #[test]
    fn errors() {
        let bad = |toml: Option<&str>, env: &[(&str, &str)]| {
            Config::from_parts(toml, &env_of(env))
                .unwrap_err()
                .to_string()
        };
        assert!(bad(None, &[("WA_GATEWAY_BIND", "nope")]).contains("bind"));
        assert!(bad(None, &[("WA_GATEWAY_MAX_INSTANCES", "-1")]).contains("max_instances"));
        assert!(bad(None, &[("WA_GATEWAY_TRUST_PROXY", "maybe")]).contains("trust_proxy"));
        assert!(bad(None, &[("WA_GATEWAY_FCM_PROJECT", "p")]).contains("fcm"));
        assert!(bad(Some("bogus = 1"), &[]).contains("bogus"));
        assert!(bad(Some("bind = ["), &[]).contains("config file"));
    }

    const EXAMPLE: &str = include_str!("../deploy/config.example.toml");

    #[test]
    fn example_config_parses_with_defaults_except_data_dir() {
        let c = Config::from_parts(Some(EXAMPLE), &env_of(&[])).unwrap();
        assert_eq!(c.bind, DEFAULT_BIND.parse().unwrap());
        assert_eq!(c.data_dir, PathBuf::from("/var/lib/wa-gateway"));
        assert_eq!(c.max_instances, DEFAULT_MAX_INSTANCES);
        assert!(!c.trust_proxy);
        assert_eq!(c.fcm, None);
    }

    #[test]
    fn example_config_fcm_lines_work_when_uncommented() {
        let text: String = EXAMPLE
            .lines()
            .map(|l| {
                l.strip_prefix("# fcm_")
                    .map_or_else(|| l.to_owned(), |rest| format!("fcm_{rest}"))
            })
            .collect::<Vec<_>>()
            .join("\n");
        let c = Config::from_parts(Some(&text), &env_of(&[])).unwrap();
        let fcm = c.fcm.expect("fcm configured");
        assert_eq!(fcm.project_id, "my-firebase-project");
        assert_eq!(
            fcm.service_account,
            PathBuf::from("/etc/wa-gateway/service-account.json")
        );
    }

    #[test]
    fn cli() {
        let args = |a: &[&str]| Cli::parse(a.iter().map(|s| (*s).to_owned()));
        assert_eq!(args(&[]), Ok(Cli::Run { config: None }));
        assert_eq!(args(&["--help"]), Ok(Cli::Help));
        assert_eq!(args(&["-h"]), Ok(Cli::Help));
        assert_eq!(
            args(&["--config", "a.toml"]),
            Ok(Cli::Run {
                config: Some("a.toml".into())
            })
        );
        assert_eq!(
            args(&["--config=b.toml"]),
            Ok(Cli::Run {
                config: Some("b.toml".into())
            })
        );
        assert!(args(&["--config"]).is_err());
        assert!(args(&["--wat"]).is_err());
    }
}

//! `wa_gateway [--config <file.toml>]`

use std::process::ExitCode;
use std::sync::Arc;

use wa_gateway::config::{Cli, Config, USAGE};
use wa_gateway::store::Store;
use wa_gateway::{AppState, serve};

#[tokio::main]
async fn main() -> ExitCode {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();
    let cli = match Cli::parse(std::env::args().skip(1)) {
        Ok(cli) => cli,
        Err(message) => {
            eprintln!("{message}\n\n{USAGE}");
            return ExitCode::from(2);
        }
    };
    let Cli::Run { config } = cli else {
        print!("{USAGE}");
        return ExitCode::SUCCESS;
    };
    match run(config.as_deref()).await {
        Ok(()) => ExitCode::SUCCESS,
        Err(message) => {
            log::error!("{message}");
            ExitCode::FAILURE
        }
    }
}

async fn run(config_path: Option<&std::path::Path>) -> Result<(), String> {
    let config = Config::load(config_path).map_err(|e| e.to_string())?;
    std::fs::create_dir_all(&config.data_dir)
        .map_err(|e| format!("cannot create {}: {e}", config.data_dir.display()))?;
    let store = Store::open(&config.db_path()).map_err(|e| e.to_string())?;

    let driver = Arc::new(wa_gateway::wa::WaRuntime::new(config.data_dir.clone()));
    let listener = tokio::net::TcpListener::bind(config.bind)
        .await
        .map_err(|e| format!("cannot bind {}: {e}", config.bind))?;
    let state = AppState::new(config, store, driver);
    let restarted = state.restart_instances().await.map_err(|e| e.to_string())?;
    log::info!(
        "listening on {}, {restarted} instance(s) restarted",
        listener.local_addr().map_err(|e| e.to_string())?
    );
    tokio::select! {
        result = serve(listener, state) => result.map_err(|e| e.to_string()),
        _ = tokio::signal::ctrl_c() => {
            log::info!("shutting down");
            Ok(())
        }
    }
}

//! twitch-bridge: read-only Twitch chat over IRC-as-WebSocket, exposed to the
//! TwitchIntegration Minecraft mod as newline-delimited JSON on loopback TCP.
//!
//! Run it standalone:
//!   twitch-bridge --channel somechannel --port 38370
//! Or stream chat to a terminal (no socket, no mod):
//!   twitch-bridge --channel somechannel --print

mod irc;
mod protocol;
mod ratelimit;
mod server;

use anyhow::{anyhow, Result};
use clap::Parser;
use serde_json::Value;
use tokio::sync::{broadcast, mpsc, watch};
use tracing_subscriber::EnvFilter;

use crate::protocol::Event;

#[derive(Debug, Parser)]
#[command(
    name = "twitch-bridge",
    version,
    about = "Twitch chat bridge for the TwitchIntegration Minecraft mod"
)]
struct Args {
    /// Twitch channel to join, without the leading '#'
    #[arg(long)]
    channel: Option<String>,

    /// Loopback TCP port the mod connects to
    #[arg(long, default_value_t = 38370)]
    port: u16,

    /// Print chat to stdout instead of opening the TCP socket
    #[arg(long)]
    print: bool,

    /// Allow the mod to stop this process with cmd.shutdown (used when the mod
    /// spawned the bridge itself)
    #[arg(long)]
    allow_shutdown: bool,
}

#[tokio::main]
async fn main() -> Result<()> {
    let args = Args::parse();
    init_tracing();

    let channel = args
        .channel
        .map(|raw| raw.trim().trim_start_matches('#').to_ascii_lowercase())
        .filter(|channel| !channel.is_empty());

    if let Some(name) = channel.as_deref() {
        if !is_valid_channel(name) {
            return Err(anyhow!(
                "'{name}' is not a valid Twitch channel name (1-25 letters, digits or underscores)"
            ));
        }
    }

    if args.print && channel.is_none() {
        return Err(anyhow!("--print requires --channel"));
    }

    let (events, _) = broadcast::channel(256);
    let (control_tx, control_rx) = mpsc::channel(16);
    let (shutdown_tx, shutdown_rx) = watch::channel(false);

    let has_channel = channel.is_some();
    let irc_events = events.clone();
    let irc_shutdown = shutdown_rx.clone();
    tokio::spawn(async move {
        irc::run(channel, irc_events, control_rx, irc_shutdown).await;
    });

    if args.print {
        tracing::info!("print mode: streaming chat to stdout, ctrl-c to stop");
        let mut receiver = events.subscribe();
        loop {
            tokio::select! {
                _ = tokio::signal::ctrl_c() => break,
                event = receiver.recv() => match event {
                    Ok(event) => print_event(&event),
                    Err(broadcast::error::RecvError::Lagged(skipped)) => {
                        tracing::warn!(skipped, "events dropped, the printer is behind");
                    }
                    Err(_) => break,
                }
            }
        }
        let _ = shutdown_tx.send(true);
        return Ok(());
    }

    let port = args.port;
    let allow_shutdown = args.allow_shutdown;
    let server_events = events.clone();
    let server_shutdown = shutdown_rx.clone();
    let server_shutdown_tx = shutdown_tx.clone();

    if has_channel {
        tracing::info!(port, "starting bridge");
    } else {
        tracing::info!(
            port,
            "starting bridge without a channel, waiting for the mod to configure one"
        );
    }

    let result = tokio::select! {
        _ = tokio::signal::ctrl_c() => {
            tracing::info!("ctrl-c received, shutting down");
            Ok(())
        }
        outcome = server::run(
            port,
            allow_shutdown,
            server_events,
            control_tx,
            server_shutdown,
            server_shutdown_tx,
        ) => outcome,
    };

    let _ = shutdown_tx.send(true);
    result
}

fn is_valid_channel(channel: &str) -> bool {
    !channel.is_empty()
        && channel.len() <= 25
        && channel
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '_')
}

fn print_event(event: &Event) {
    let data = &event.data;
    match event.kind.as_str() {
        "chat.message" => {
            let name = data
                .get("displayName")
                .and_then(Value::as_str)
                .unwrap_or("?");
            let text = data.get("message").and_then(Value::as_str).unwrap_or("");
            println!("[chat] {name}: {text}");
        }
        "meta.status" => {
            let state = data.get("state").and_then(Value::as_str).unwrap_or("?");
            let channel = data.get("channel").and_then(Value::as_str).unwrap_or("-");
            println!("[status] {state} ({channel})");
        }
        "meta.error" => {
            let code = data.get("code").and_then(Value::as_str).unwrap_or("error");
            let message = data.get("message").and_then(Value::as_str).unwrap_or("");
            eprintln!("[error] {code}: {message}");
        }
        _ => {}
    }
}

fn init_tracing() {
    let filter = EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info"));
    tracing_subscriber::fmt()
        .with_env_filter(filter)
        .with_target(false)
        .init();
}

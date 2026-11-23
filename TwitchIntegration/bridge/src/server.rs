//! Loopback TCP server the Minecraft mod connects to.

use std::time::Duration;

use anyhow::{Context, Result};
use serde_json::Value;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::tcp::OwnedWriteHalf;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{broadcast, mpsc, watch};
use tokio::time::{interval, MissedTickBehavior};

use crate::protocol::{Control, Event};
use crate::ratelimit::RateLimiter;

enum Action {
    Continue,
    Close,
}

pub async fn run(
    port: u16,
    allow_shutdown: bool,
    events: broadcast::Sender<Event>,
    control: mpsc::Sender<Control>,
    mut shutdown: watch::Receiver<bool>,
    shutdown_tx: watch::Sender<bool>,
) -> Result<()> {
    let listener = TcpListener::bind(("127.0.0.1", port))
        .await
        .with_context(|| format!("binding 127.0.0.1:{port}"))?;
    tracing::info!(port, "listening for the Minecraft mod");

    loop {
        let client_shutdown = shutdown.clone();
        tokio::select! {
            _ = shutdown.changed() => break,
            accepted = listener.accept() => {
                let (stream, addr) = accepted.context("accepting a mod connection")?;
                tracing::info!(%addr, "mod connected");
                let result = handle_client(
                    stream,
                    allow_shutdown,
                    &events,
                    &control,
                    client_shutdown,
                    shutdown_tx.clone(),
                )
                .await;
                match result {
                    Ok(()) => tracing::info!("mod disconnected"),
                    Err(err) => tracing::warn!(%err, "mod connection ended with an error"),
                }
            }
        }
    }

    Ok(())
}

async fn handle_client(
    stream: TcpStream,
    allow_shutdown: bool,
    events: &broadcast::Sender<Event>,
    control: &mpsc::Sender<Control>,
    mut shutdown: watch::Receiver<bool>,
    shutdown_tx: watch::Sender<bool>,
) -> Result<()> {
    stream.set_nodelay(true)?;
    let (read_half, mut write_half) = stream.into_split();
    let mut lines = BufReader::new(read_half).lines();
    let mut subscription = events.subscribe();
    let mut limiter = RateLimiter::new(40, Duration::from_secs(1));
    let mut heartbeat = interval(Duration::from_secs(15));
    heartbeat.set_missed_tick_behavior(MissedTickBehavior::Delay);

    write_event(&mut write_half, &Event::hello()).await?;

    loop {
        tokio::select! {
            _ = shutdown.changed() => return Ok(()),
            _ = heartbeat.tick() => {
                if limiter.allow() {
                    write_event(&mut write_half, &Event::ping()).await?;
                }
            }
            line = lines.next_line() => {
                let Some(line) = line? else {
                    return Ok(());
                };
                let action = handle_command(
                    &line,
                    allow_shutdown,
                    control,
                    &shutdown_tx,
                    &mut write_half,
                )
                .await?;
                if matches!(action, Action::Close) {
                    return Ok(());
                }
            }
            event = subscription.recv() => match event {
                Ok(event) => {
                    if limiter.allow() {
                        write_event(&mut write_half, &event).await?;
                    } else {
                        tracing::debug!("dropping an event: output rate limit");
                    }
                }
                Err(broadcast::error::RecvError::Lagged(skipped)) => {
                    tracing::warn!(skipped, "mod is falling behind, events dropped");
                }
                Err(broadcast::error::RecvError::Closed) => return Ok(()),
            }
        }
    }
}

async fn handle_command(
    line: &str,
    allow_shutdown: bool,
    control: &mpsc::Sender<Control>,
    shutdown_tx: &watch::Sender<bool>,
    write: &mut OwnedWriteHalf,
) -> Result<Action> {
    let line = line.trim();
    if line.is_empty() {
        return Ok(Action::Continue);
    }

    let value: Value = match serde_json::from_str(line) {
        Ok(value) => value,
        Err(err) => {
            tracing::warn!(%err, "ignoring a malformed line from the mod");
            return Ok(Action::Continue);
        }
    };

    let kind = value.get("type").and_then(Value::as_str).unwrap_or("");
    let data = value.get("data").cloned().unwrap_or(Value::Null);

    match kind {
        "cmd.configure" => {
            let channel = data
                .get("channel")
                .and_then(Value::as_str)
                .unwrap_or("")
                .trim()
                .trim_start_matches('#')
                .to_ascii_lowercase();
            if !channel.is_empty() && control.send(Control::SetChannel(channel)).await.is_err() {
                return Ok(Action::Close);
            }
        }
        "cmd.pong" => {}
        "cmd.shutdown" => {
            if allow_shutdown {
                let _ = shutdown_tx.send(true);
            }
            return Ok(Action::Close);
        }
        "cmd.say" => {
            write_event(
                write,
                &Event::error(
                    "unsupported",
                    "this build is read-only; cmd.say is not implemented",
                ),
            )
            .await?;
        }
        "" => tracing::debug!("line without a type"),
        other => tracing::debug!(other, "unhandled command"),
    }

    Ok(Action::Continue)
}

async fn write_event(write: &mut OwnedWriteHalf, event: &Event) -> Result<()> {
    let mut payload = serde_json::to_vec(event)?;
    payload.push(b'\n');
    write.write_all(&payload).await?;
    write.flush().await?;
    Ok(())
}

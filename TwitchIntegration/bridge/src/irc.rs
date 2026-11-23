//! Anonymous, read-only Twitch chat over IRC-as-WebSocket.
//!
//! Twitch's chat gateway is plain IRC tunnelled through a websocket. Read-only
//! access needs no credentials: pick a `justinfanNNNNN` nick, ask for tags and
//! commands, join the channel. No OAuth token is involved anywhere in this file.

use std::collections::HashMap;
use std::time::Duration;

use anyhow::{Context, Result};
use futures_util::stream::SplitSink;
use futures_util::{SinkExt, StreamExt};
use rand::Rng;
use tokio::net::TcpStream;
use tokio::sync::{broadcast, mpsc, watch};
use tokio::time::sleep;
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::{connect_async, MaybeTlsStream, WebSocketStream};

use crate::protocol::{ChatMessage, Control, Event};

const IRC_URL: &str = "wss://irc-ws.chat.twitch.tv:443";
const BASE_BACKOFF: Duration = Duration::from_secs(1);
const MAX_BACKOFF: Duration = Duration::from_secs(60);

type WsSink = SplitSink<WebSocketStream<MaybeTlsStream<TcpStream>>, Message>;

enum Exit {
    Shutdown,
    Reconnect,
    ChannelChanged(String),
    Fatal(String),
}

/// Supervises the IRC connection: connects, reconnects with backoff, and
/// re-targets when the mod asks for a different channel.
pub async fn run(
    initial_channel: Option<String>,
    events: broadcast::Sender<Event>,
    mut control: mpsc::Receiver<Control>,
    mut shutdown: watch::Receiver<bool>,
) {
    let mut channel = initial_channel;
    let mut backoff = BASE_BACKOFF;

    loop {
        if *shutdown.borrow() {
            break;
        }

        let Some(current) = channel.clone() else {
            let _ = events.send(Event::status("idle", None));
            tokio::select! {
                cmd = control.recv() => match cmd {
                    Some(Control::SetChannel(next)) => channel = Some(next),
                    None => break,
                },
                _ = shutdown.changed() => break,
            }
            continue;
        };

        let _ = events.send(Event::status("connecting", Some(&current)));

        let outcome = connect_and_read(&current, &events, &mut control, &mut shutdown).await;

        match outcome {
            Ok(Exit::Shutdown) => break,
            Ok(Exit::ChannelChanged(next)) => {
                tracing::info!(channel = %next, "switching channel");
                channel = Some(next);
                backoff = BASE_BACKOFF;
                continue;
            }
            Ok(Exit::Reconnect) => {
                let _ = events.send(Event::status("reconnecting", Some(&current)));
            }
            Ok(Exit::Fatal(reason)) => {
                let _ = events.send(Event::error("irc", reason));
                let _ = events.send(Event::status("reconnecting", Some(&current)));
            }
            Err(err) => {
                let _ = events.send(Event::error("irc", format!("{err:#}")));
                let _ = events.send(Event::status("reconnecting", Some(&current)));
            }
        }

        let jitter = Duration::from_millis(rand::thread_rng().gen_range(0..=750));
        let wait = (backoff + jitter).min(MAX_BACKOFF);
        tracing::info!(?wait, "reconnecting to Twitch");

        tokio::select! {
            _ = sleep(wait) => {}
            cmd = control.recv() => match cmd {
                Some(Control::SetChannel(next)) => {
                    channel = Some(next);
                    backoff = BASE_BACKOFF;
                }
                None => break,
            },
            _ = shutdown.changed() => break,
        }

        backoff = (backoff * 2).min(MAX_BACKOFF);
    }

    let _ = events.send(Event::status("disconnected", channel.as_deref()));
}

async fn connect_and_read(
    channel: &str,
    events: &broadcast::Sender<Event>,
    control: &mut mpsc::Receiver<Control>,
    shutdown: &mut watch::Receiver<bool>,
) -> Result<Exit> {
    let (socket, _response) = connect_async(IRC_URL)
        .await
        .context("opening the Twitch IRC websocket")?;
    let (mut sink, mut stream) = socket.split();

    let nick = format!(
        "justinfan{:05}",
        rand::thread_rng().gen_range(0..100_000u32)
    );
    send(&mut sink, "CAP REQ :twitch.tv/tags twitch.tv/commands").await?;
    send(&mut sink, "PASS SCHMOOPIIE").await?;
    send(&mut sink, &format!("NICK {nick}")).await?;

    let mut joined = false;

    loop {
        tokio::select! {
            _ = shutdown.changed() => {
                let _ = send(&mut sink, &format!("PART #{channel}")).await;
                let _ = sink.close().await;
                return Ok(Exit::Shutdown);
            }
            cmd = control.recv() => match cmd {
                Some(Control::SetChannel(next)) => {
                    if !next.eq_ignore_ascii_case(channel) {
                        let _ = sink.close().await;
                        return Ok(Exit::ChannelChanged(next));
                    }
                }
                None => {
                    let _ = sink.close().await;
                    return Ok(Exit::Shutdown);
                }
            },
            frame = stream.next() => {
                let Some(frame) = frame else {
                    return Ok(Exit::Reconnect);
                };
                let frame = frame.context("reading the Twitch IRC websocket")?;
                match frame {
                    Message::Text(text) => {
                        for line in text.as_str().lines() {
                            if line.is_empty() {
                                continue;
                            }
                            if let Some(exit) =
                                handle_line(line, channel, &mut sink, &mut joined, events).await?
                            {
                                return Ok(exit);
                            }
                        }
                    }
                    Message::Close(_) => return Ok(Exit::Reconnect),
                    Message::Ping(payload) => {
                        sink.send(Message::Pong(payload))
                            .await
                            .context("replying to a websocket ping")?;
                    }
                    _ => {}
                }
            }
        }
    }
}

async fn send(sink: &mut WsSink, line: &str) -> Result<()> {
    sink.send(Message::Text(line.into()))
        .await
        .context("sending to the Twitch IRC websocket")
}

async fn handle_line(
    line: &str,
    channel: &str,
    sink: &mut WsSink,
    joined: &mut bool,
    events: &broadcast::Sender<Event>,
) -> Result<Option<Exit>> {
    let parsed = ParsedLine::parse(line);

    if tracing::enabled!(tracing::Level::TRACE) {
        tracing::trace!(command = %parsed.command, "irc line");
    }

    match parsed.command.as_str() {
        "PING" => {
            let token = parsed
                .params
                .last()
                .map(String::as_str)
                .unwrap_or("tmi.twitch.tv");
            send(sink, &format!("PONG :{token}")).await?;
        }
        "001" => {
            if !*joined {
                send(sink, &format!("JOIN #{channel}")).await?;
                *joined = true;
                tracing::info!(channel, "joined Twitch channel (read-only)");
                let _ = events.send(Event::status("connected", Some(channel)));
            }
        }
        "RECONNECT" => return Ok(Some(Exit::Reconnect)),
        "PRIVMSG" => {
            if let Some(message) = chat_from(&parsed) {
                let _ = events.send(Event::chat(&message));
            }
        }
        "NOTICE" => {
            let text = parsed.params.last().map(String::as_str).unwrap_or("");
            if text
                .to_ascii_lowercase()
                .contains("login authentication failed")
            {
                return Ok(Some(Exit::Fatal(text.to_string())));
            }
            tracing::warn!(notice = text, "Twitch notice");
        }
        _ => {}
    }

    Ok(None)
}

fn chat_from(line: &ParsedLine) -> Option<ChatMessage> {
    let body = strip_ctcp(&line.params.get(1)?.clone());
    let prefix = line.prefix.as_deref().unwrap_or("");
    let user = prefix.split('!').next().unwrap_or(prefix).to_string();
    if user.is_empty() {
        return None;
    }

    let display_name = line
        .tag("display-name")
        .filter(|name| !name.is_empty())
        .unwrap_or(&user)
        .to_string();
    let badges = line.tag("badges").unwrap_or("");
    let is_broadcaster = badges.split(',').any(|b| b.starts_with("broadcaster/"));
    let is_mod = is_broadcaster || line.tag("mod") == Some("1");
    let is_subscriber = badges.split(',').any(|b| b.starts_with("subscriber/"))
        || line.tag("subscriber") == Some("1");

    Some(ChatMessage {
        id: line.tag("id").unwrap_or("").to_string(),
        user,
        display_name,
        color: line.tag("color").unwrap_or("").to_string(),
        message: body,
        bits: line
            .tag("bits")
            .and_then(|bits| bits.parse::<i64>().ok())
            .unwrap_or(0),
        is_mod,
        is_subscriber,
        is_broadcaster,
    })
}

/// Twitch wraps `/me` messages as `\x01ACTION text\x01`. Keep the text, drop the
/// CTCP framing so the game's chat doesn't show control characters.
fn strip_ctcp(message: &str) -> String {
    if message.len() < 2 || !message.starts_with('\u{1}') || !message.ends_with('\u{1}') {
        return message.to_string();
    }
    let inner = &message[1..message.len() - 1];
    inner.strip_prefix("ACTION ").unwrap_or(inner).to_string()
}

struct ParsedLine {
    tags: HashMap<String, String>,
    prefix: Option<String>,
    command: String,
    params: Vec<String>,
}

impl ParsedLine {
    fn parse(raw: &str) -> Self {
        let mut rest = raw.trim_end_matches(['\r', '\n']);
        let mut tags = HashMap::new();

        if let Some(stripped) = rest.strip_prefix('@') {
            let (tag_part, remainder) = stripped.split_once(' ').unwrap_or((stripped, ""));
            tags = parse_tags(tag_part);
            rest = remainder;
        }

        let mut prefix = None;
        if let Some(stripped) = rest.strip_prefix(':') {
            if let Some((value, remainder)) = stripped.split_once(' ') {
                prefix = Some(value.to_string());
                rest = remainder;
            }
        }

        let (command, mut rest) = rest.split_once(' ').unwrap_or((rest, ""));
        let command = command.to_string();
        let mut params = Vec::new();

        while !rest.is_empty() {
            if let Some(trailing) = rest.strip_prefix(':') {
                params.push(trailing.to_string());
                break;
            }
            let (token, remainder) = rest.split_once(' ').unwrap_or((rest, ""));
            params.push(token.to_string());
            rest = remainder;
        }

        Self {
            tags,
            prefix,
            command,
            params,
        }
    }

    fn tag(&self, name: &str) -> Option<&str> {
        self.tags.get(name).map(String::as_str)
    }
}

fn parse_tags(raw: &str) -> HashMap<String, String> {
    raw.split(';')
        .filter_map(|pair| {
            let (key, value) = pair.split_once('=')?;
            Some((key.to_string(), unescape_tag(value)))
        })
        .collect()
}

fn unescape_tag(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    let mut chars = value.chars();
    while let Some(c) = chars.next() {
        if c != '\\' {
            out.push(c);
            continue;
        }
        match chars.next() {
            Some('s') => out.push(' '),
            Some(':') => out.push(';'),
            Some('\\') => out.push('\\'),
            Some('r') => out.push('\r'),
            Some('n') => out.push('\n'),
            Some(other) => out.push(other),
            None => {}
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_a_tagged_privmsg() {
        let line = "@badge-info=subscriber/12;bits=100;color=#1E90FF;display-name=Someone;\
                    mod=0;subscriber=1;id=abc-123 \
                    :someone!someone@someone.tmi.twitch.tv PRIVMSG #chan :hello world";
        let parsed = ParsedLine::parse(line);
        let message = chat_from(&parsed).expect("message");

        assert_eq!(parsed.command, "PRIVMSG");
        assert_eq!(message.user, "someone");
        assert_eq!(message.display_name, "Someone");
        assert_eq!(message.color, "#1E90FF");
        assert_eq!(message.message, "hello world");
        assert_eq!(message.bits, 100);
        assert!(message.is_subscriber);
        assert!(!message.is_mod);
        assert_eq!(message.id, "abc-123");
    }

    #[test]
    fn ignores_ws_privmsg_without_prefix() {
        let line = "PRIVMSG #chan :hi";
        let parsed = ParsedLine::parse(line);
        assert!(chat_from(&parsed).is_none());
    }

    #[test]
    fn strips_ctcp_action_framing() {
        assert_eq!(strip_ctcp("\u{1}ACTION waves\u{1}"), "waves");
        assert_eq!(strip_ctcp("plain message"), "plain message");
    }

    #[test]
    fn unescapes_tag_values() {
        assert_eq!(unescape_tag(r"a\sb\:c\\d"), "a b;c\\d");
    }

    #[test]
    fn detects_broadcaster() {
        let line = "@badges=broadcaster/1;mod=0 :streamer!streamer@streamer.tmi.twitch.tv PRIVMSG #chan :hey";
        let parsed = ParsedLine::parse(line);
        let message = chat_from(&parsed).expect("message");

        assert!(message.is_broadcaster);
        assert!(message.is_mod);
    }
}

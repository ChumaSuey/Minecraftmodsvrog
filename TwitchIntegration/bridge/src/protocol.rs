//! Wire protocol shared with the Java mod.
//!
//! One JSON object per line, over a loopback TCP socket. Every message has the
//! shape `{"v":1,"type":"...","ts":...,"data":{...}}`. Unknown types are ignored
//! by the other side, so new events can be added without breaking older builds.

use std::time::{SystemTime, UNIX_EPOCH};

use serde::Serialize;
use serde_json::{json, Value};

pub const PROTOCOL_VERSION: u64 = 1;

#[derive(Debug, Clone, Serialize)]
pub struct Event {
    pub v: u64,
    #[serde(rename = "type")]
    pub kind: String,
    pub ts: i64,
    pub data: Value,
}

impl Event {
    pub fn new(kind: impl Into<String>, data: Value) -> Self {
        Self {
            v: PROTOCOL_VERSION,
            kind: kind.into(),
            ts: now_secs(),
            data,
        }
    }

    pub fn hello() -> Self {
        Self::new(
            "hello",
            json!({ "bridge": "twitch-bridge", "protocol": PROTOCOL_VERSION }),
        )
    }

    pub fn chat(message: &ChatMessage) -> Self {
        Self::new(
            "chat.message",
            json!({
                "id": message.id,
                "user": message.user,
                "displayName": message.display_name,
                "color": message.color,
                "message": message.message,
                "bits": message.bits,
                "isMod": message.is_mod,
                "isSubscriber": message.is_subscriber,
                "isBroadcaster": message.is_broadcaster,
            }),
        )
    }

    pub fn status(state: &str, channel: Option<&str>) -> Self {
        Self::new("meta.status", json!({ "state": state, "channel": channel }))
    }

    pub fn error(code: &str, message: impl Into<String>) -> Self {
        Self::new(
            "meta.error",
            json!({ "code": code, "message": message.into() }),
        )
    }

    pub fn ping() -> Self {
        Self::new("meta.ping", json!({}))
    }
}

/// A single chat line, already stripped of IRC framing.
#[derive(Debug, Clone, Default)]
pub struct ChatMessage {
    pub id: String,
    pub user: String,
    pub display_name: String,
    pub color: String,
    pub message: String,
    pub bits: i64,
    pub is_mod: bool,
    pub is_subscriber: bool,
    pub is_broadcaster: bool,
}

/// Commands coming back from the mod.
#[derive(Debug, Clone)]
pub enum Control {
    /// Switch to another channel. The IRC connection is rebuilt only when the
    /// channel actually differs.
    SetChannel(String),
}

fn now_secs() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

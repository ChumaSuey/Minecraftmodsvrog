package com.vrogil.bridge;

/** State of the loopback link to the Rust bridge. */
public enum BridgeState {
	STOPPED,
	CONNECTING,
	CONNECTED
}

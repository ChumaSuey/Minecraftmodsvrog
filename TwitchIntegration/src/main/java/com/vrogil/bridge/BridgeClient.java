package com.vrogil.bridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.vrogil.TwitchIntegrationMod;
import com.vrogil.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps a newline-delimited JSON connection to the Rust bridge alive.
 *
 * <p>One supervisor thread owns reconnects and reads; a writer thread drains an
 * outbound queue. Everything that reaches gameplay is handed to {@code chatSink}
 * and must be marshalled onto the server thread there.
 */
public final class BridgeClient {
	private static final Logger LOG = LoggerFactory.getLogger("twitchintegration");
	private static final int CONNECT_TIMEOUT_MS = 3_000;
	/** The bridge pings every 15 s, so silence this long means the link is dead. */
	private static final int READ_TIMEOUT_MS = 45_000;
	private static final int BASE_BACKOFF_MS = 1_000;
	private static final int QUEUE_CAPACITY = 256;
	private static final long SPAWN_RETRY_MS = 30_000L;

	private final Supplier<ModConfig> config;
	private final Consumer<ChatMessage> chatSink;
	private final BridgeProcess process = new BridgeProcess();
	private final BlockingQueue<String> outbound = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
	private final Object writeLock = new Object();

	private volatile boolean running;
	private volatile BridgeState state = BridgeState.STOPPED;
	private volatile String twitchState = "unknown";
	private volatile int port;
	private volatile long messagesReceived;
	private volatile long messagesDropped;
	private volatile Socket socket;
	private volatile OutputStream output;
	private volatile boolean writerRunning;

	private Thread supervisor;
	private volatile Thread writer;

	public BridgeClient(Supplier<ModConfig> config, Consumer<ChatMessage> chatSink) {
		this.config = config;
		this.chatSink = chatSink;
		this.port = config.get().transport.port;
	}

	public synchronized void start() {
		if (running) {
			return;
		}
		running = true;
		port = config.get().transport.port;
		supervisor = new Thread(this::supervise, "twitchintegration-supervisor");
		supervisor.setDaemon(true);
		supervisor.start();
	}

	public synchronized void stop() {
		if (process.isAlive()) {
			send(Protocol.shutdown());
			sleepQuietly(200);
		}

		running = false;
		closeSocket();

		Thread current = supervisor;
		supervisor = null;
		if (current != null) {
			current.interrupt();
			try {
				current.join(1_500);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		stopWriter();
		process.close();

		state = BridgeState.STOPPED;
		twitchState = "stopped";
	}

	/** Apply a reloaded config without dropping the world. */
	public synchronized void applyConfig() {
		ModConfig cfg = config.get();
		if (cfg.transport.port != port) {
			boolean wasRunning = running;
			LOG.info("[twitch] port changed from {} to {}, restarting the bridge link", port, cfg.transport.port);
			stop();
			port = cfg.transport.port;
			if (wasRunning) {
				start();
			}
			return;
		}
		send(Protocol.configure(cfg.channel));
	}

	public BridgeState state() {
		return state;
	}

	public String twitchState() {
		return twitchState;
	}

	public long messagesReceived() {
		return messagesReceived;
	}

	public long messagesDropped() {
		return messagesDropped;
	}

	public int port() {
		return port;
	}

	public boolean isRunning() {
		return running;
	}

	public void send(String line) {
		if (!running) {
			return;
		}
		if (!outbound.offer(line)) {
			messagesDropped++;
			LOG.debug("[twitch] outbound queue full, dropping a command");
		}
	}

	private void supervise() {
		int failures = 0;
		int backoff = BASE_BACKOFF_MS;
		long lastSpawnAttempt = 0L;

		while (running) {
			ModConfig cfg = config.get();
			port = cfg.transport.port;
			state = BridgeState.CONNECTING;
			boolean linked = false;

			try (Socket candidate = new Socket()) {
				candidate.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_TIMEOUT_MS);
				candidate.setTcpNoDelay(true);
				candidate.setSoTimeout(READ_TIMEOUT_MS);

				socket = candidate;
				output = candidate.getOutputStream();

				startWriter(candidate);
				writeDirect(Protocol.hello());
				writeDirect(Protocol.configure(cfg.channel));

				state = BridgeState.CONNECTED;
				linked = true;
				failures = 0;
				backoff = BASE_BACKOFF_MS;
				LOG.info("[twitch] connected to the bridge on 127.0.0.1:{}", port);
				TwitchIntegrationMod.handleStatus("link-established");

				readLoop(candidate);
			} catch (SocketTimeoutException e) {
				if (running) {
					LOG.warn("[twitch] the bridge went quiet, reconnecting");
				}
			} catch (IOException e) {
				if (!running) {
					break;
				}
				if (failures == 0) {
					LOG.warn("[twitch] no bridge listening on 127.0.0.1:{} ({})", port, e.getMessage());
				} else {
					LOG.debug("[twitch] bridge connect failed: {}", e.getMessage());
				}
				failures++;
			} catch (RuntimeException e) {
				LOG.warn("[twitch] unexpected bridge error", e);
				failures++;
			} finally {
				stopWriter();
				closeSocket();
				state = BridgeState.STOPPED;
				if (linked) {
					TwitchIntegrationMod.handleStatus("link-lost");
				}
			}

			if (!running) {
				break;
			}

			if (running && cfg.transport.autoSpawn
					&& System.currentTimeMillis() - lastSpawnAttempt > SPAWN_RETRY_MS) {
				lastSpawnAttempt = System.currentTimeMillis();
				process.spawn(cfg);
			}

			sleepQuietly(backoff + ThreadLocalRandom.current().nextInt(250));
			backoff = Math.min(backoff * 2, Math.max(5, cfg.transport.reconnectMaxSeconds) * 1000);
		}

		twitchState = "stopped";
	}

	private void readLoop(Socket connection) throws IOException {
		BufferedReader reader = new BufferedReader(
				new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
		String line;
		while (running) {
			line = reader.readLine();
			if (line == null) {
				return;
			}
			handleLine(line);
		}
	}

	private void handleLine(String line) {
		Protocol.Incoming incoming = Protocol.parse(line);
		if (incoming == null) {
			messagesDropped++;
			LOG.debug("[twitch] dropped an unparseable bridge line");
			return;
		}

		switch (incoming.type()) {
			case "chat.message" -> {
				messagesReceived++;
				ChatMessage message = Protocol.chat(incoming.data());
				if (message == null) {
					messagesDropped++;
					return;
				}
				try {
					chatSink.accept(message);
				} catch (RuntimeException e) {
					LOG.warn("[twitch] chat handler failed", e);
				}
			}
			case "meta.ping" -> send(Protocol.pong());
			case "meta.status" -> {
				String next = Protocol.string(incoming.data(), "state", twitchState);
				if (!next.equals(twitchState)) {
					twitchState = next;
					LOG.info("[twitch] Twitch connection state: {}", next);
					TwitchIntegrationMod.handleStatus(next);
				}
			}
			case "meta.error" -> LOG.warn(
					"[twitch] bridge error {}: {}",
					Protocol.string(incoming.data(), "code", "error"),
					Protocol.string(incoming.data(), "message", "")
			);
			case "hello" -> LOG.debug("[twitch] bridge handshake accepted");
			default -> {
				LOG.debug("[twitch] unhandled event {}", incoming.type());
				TwitchIntegrationMod.handleRawEvent(incoming.type(), incoming.data());
			}
		}
	}

	private void startWriter(Socket connection) {
		writerRunning = true;
		writer = new Thread(() -> {
			try {
				while (writerRunning) {
					String line = outbound.poll(500, TimeUnit.MILLISECONDS);
					if (line == null) {
						continue;
					}
					synchronized (writeLock) {
						OutputStream out = output;
						if (out == null) {
							return;
						}
						out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
						out.flush();
					}
				}
			} catch (IOException e) {
				LOG.debug("[twitch] writer stopped: {}", e.getMessage());
				closeQuietly(connection);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}, "twitchintegration-writer");
		writer.setDaemon(true);
		writer.start();
	}

	private void stopWriter() {
		writerRunning = false;
		Thread current = writer;
		writer = null;
		if (current != null) {
			current.interrupt();
			try {
				current.join(500);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		outbound.clear();
	}

	private void writeDirect(String line) throws IOException {
		synchronized (writeLock) {
			OutputStream out = output;
			if (out == null) {
				throw new IOException("bridge socket is not open");
			}
			out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
			out.flush();
		}
	}

	private void closeSocket() {
		Socket current = socket;
		socket = null;
		output = null;
		closeQuietly(current);
	}

	private static void closeQuietly(Socket target) {
		if (target == null) {
			return;
		}
		try {
			target.close();
		} catch (IOException ignored) {
			// already gone
		}
	}

	private static void sleepQuietly(int millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}

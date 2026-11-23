package com.vrogil.bridge;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.vrogil.TwitchIntegrationMod;
import com.vrogil.config.ModConfig;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds and (optionally) starts the Rust bridge binary.
 *
 * <p>Resolution order: explicit {@code transport.bridgePath}, the binary bundled
 * in the jar (extracted to {@code config/twitchintegration/bin/}), a local
 * {@code bridge/target/release/} build, then {@code PATH}. Running the bridge
 * yourself is the default; spawning it is opt-in.
 */
public final class BridgeProcess implements AutoCloseable {
	private static final Logger LOG = LoggerFactory.getLogger("twitchintegration");
	private static final String RESOURCE_ROOT = "assets/twitchintegration/bin/";

	private Process process;
	private Path binary;

	public Optional<Path> locate(ModConfig config) {
		String configured = config.transport.bridgePath;
		if (configured != null && !configured.isBlank()) {
			try {
				Path path = Path.of(expandHome(configured));
				if (Files.isRegularFile(path)) {
					return Optional.of(path);
				}
				LOG.warn("[twitch] transport.bridgePath points at a missing file: {}", path);
			} catch (RuntimeException e) {
				LOG.warn("[twitch] transport.bridgePath is not a valid path: {}", configured);
			}
		}

		Optional<Path> bundled = extractBundled();
		if (bundled.isPresent()) {
			return bundled;
		}

		Path dev = Path.of("bridge", "target", "release", executableName());
		if (Files.isRegularFile(dev)) {
			return Optional.of(dev.toAbsolutePath());
		}

		return fromPath();
	}

	public synchronized boolean spawn(ModConfig config) {
		if (process != null && process.isAlive()) {
			return true;
		}

		Optional<Path> located = locate(config);
		if (located.isEmpty()) {
			LOG.warn("[twitch] no bridge binary found. Start twitch-bridge manually, "
					+ "set transport.bridgePath, or build it with 'cargo build --release' in bridge/.");
			return false;
		}
		binary = located.get();

		List<String> command = new ArrayList<>();
		command.add(binary.toString());
		if (!config.channel.isBlank()) {
			command.add("--channel");
			command.add(config.channel);
		}
		command.add("--port");
		command.add(Integer.toString(config.transport.port));
		command.add("--allow-shutdown");

		try {
			ProcessBuilder builder = new ProcessBuilder(command);
			builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
			process = builder.start();
			LOG.info("[twitch] started bridge {} (pid {})", binary.getFileName(), process.pid());
			pumpErrors(process);
			return true;
		} catch (IOException e) {
			LOG.warn("[twitch] could not start {}: {}", binary, e.getMessage());
			return false;
		}
	}

	public synchronized boolean isAlive() {
		return process != null && process.isAlive();
	}

	public Optional<Path> binaryPath() {
		return Optional.ofNullable(binary);
	}

	@Override
	public synchronized void close() {
		Process current = process;
		process = null;
		if (current == null || !current.isAlive()) {
			return;
		}
		current.destroy();
		try {
			if (!current.waitFor(1, TimeUnit.SECONDS)) {
				current.destroyForcibly();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			current.destroyForcibly();
		}
	}

	private Optional<Path> extractBundled() {
		String platform = platformId();
		String resource = RESOURCE_ROOT + platform + "/" + executableName();
		Optional<Path> source = FabricLoader.getInstance()
				.getModContainer(TwitchIntegrationMod.MOD_ID)
				.flatMap(container -> container.findPath(resource));
		if (source.isEmpty()) {
			return Optional.empty();
		}

		Path sourcePath = source.get();
		Path target = FabricLoader.getInstance().getConfigDir()
				.resolve("twitchintegration")
				.resolve("bin")
				.resolve(platform)
				.resolve(executableName());

		try {
			if (sourcePath.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
				return Optional.of(target);
			}
			Files.createDirectories(target.getParent());
			if (!Files.isRegularFile(target) || Files.size(target) != Files.size(sourcePath)) {
				try (InputStream input = Files.newInputStream(sourcePath)) {
					Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException e) {
					// Most likely a running bridge holds the file open on Windows.
					if (!Files.isRegularFile(target)) {
						throw e;
					}
					LOG.warn("[twitch] could not refresh {}, using the existing binary", target);
				}
				LOG.info("[twitch] extracted the bundled bridge to {}", target);
			}
			makeExecutable(target);
			return Optional.of(target);
		} catch (IOException e) {
			LOG.warn("[twitch] could not extract the bundled bridge binary: {}", e.getMessage());
			return Optional.empty();
		}
	}

	private static Optional<Path> fromPath() {
		String pathEnv = System.getenv("PATH");
		if (pathEnv == null) {
			return Optional.empty();
		}
		for (String entry : pathEnv.split(File.pathSeparator)) {
			if (entry.isBlank()) {
				continue;
			}
			Path candidate = Path.of(entry, executableName());
			if (Files.isRegularFile(candidate)) {
				return Optional.of(candidate);
			}
		}
		return Optional.empty();
	}

	private static void makeExecutable(Path path) {
		path.toFile().setExecutable(true, true);
		try {
			Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-x---"));
		} catch (IOException | UnsupportedOperationException ignored) {
			// Windows: setExecutable above is all there is.
		}
	}

	private static String expandHome(String path) {
		if (!path.startsWith("~")) {
			return path;
		}
		return System.getProperty("user.home") + path.substring(1);
	}

	public static String platformId() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		String osPart = os.contains("win") ? "windows" : (os.contains("mac") || os.contains("darwin")) ? "macos" : "linux";
		String archPart = arch.contains("aarch64") || arch.contains("arm64") ? "aarch64" : "x86_64";
		return osPart + "-" + archPart;
	}

	public static String executableName() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
				? "twitch-bridge.exe"
				: "twitch-bridge";
	}

	private void pumpErrors(Process started) {
		Thread thread = new Thread(() -> {
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(started.getErrorStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					LOG.info("[bridge] {}", line);
				}
			} catch (IOException ignored) {
				// the process exited; nothing left to read
			}
		}, "twitchintegration-bridge-stderr");
		thread.setDaemon(true);
		thread.start();
	}
}

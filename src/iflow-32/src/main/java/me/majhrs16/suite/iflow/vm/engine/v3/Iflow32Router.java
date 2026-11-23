package me.majhrs16.suite.iflow.vm.engine.v3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import me.majhrs16.suite.api.channel.Channel;
import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.iflow.PolicyTarget;
import me.majhrs16.suite.api.iflow.RateLimiter;
import me.majhrs16.suite.api.iflow.RouteDecision;
import me.majhrs16.suite.api.iflow.RouteOutcome;
import me.majhrs16.suite.api.iflow.Router;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.SyncBus;
import me.majhrs16.suite.iflow.vm.execution.v3.CoreCountConfig;
import me.majhrs16.suite.iflow.vm.execution.v3.ExecutionQuantum;
import me.majhrs16.suite.iflow.vm.execution.v3.ExecutionReport;
import me.majhrs16.suite.iflow.vm.execution.v3.ExecutionStatus;
import me.majhrs16.suite.iflow.vm.execution.v3.MachineRuntime;
import me.majhrs16.suite.iflow.vm.execution.v3.WatchdogConfig;
import me.majhrs16.suite.iflow.vm.host.v3.HostAbi;
import me.majhrs16.suite.iflow.vm.host.v3.HostServiceException;
import me.majhrs16.suite.iflow.vm.host.v3.V3HostBridge;
import me.majhrs16.suite.iflow.vm.isa.v3.CsrFile;
import me.majhrs16.suite.iflow.vm.isa.v3.VirtualClock;
import me.majhrs16.suite.iflow.vm.memory.v3.HostMailbox;
import me.majhrs16.suite.iflow.vm.memory.v3.MachineMemory;
import me.majhrs16.suite.iflow.vm.rom.RomImage;
import me.majhrs16.suite.iflow.vm.rom.RomParser;

/**
 * Router of the {@code iflow-32} engine.
 *
 * <p>Loads one validated v3 ROM ({@code policy.flow}) and evaluates each
 * {@code (message × recipient)} pair on a real v3 machine. The host maps the
 * ROM, hands a {@code MIDENT} to the guest and lets the guest policy call
 * {@code HOST}. The guest returns one {@code ROUTE_DECISION}; the host applies
 * the committed journal and maps the ABI target to a {@link PolicyTarget}.</p>
 *
 * <p>Lifecycle of this slice: the guest kernel loads exactly one
 * {@code PROGRAM} during boot and then idles in {@code HALT}. Every message is
 * published through the host mailbox ({@code TYPE_MESSAGE} + IRQ 5); the kernel
 * reads the handle, assigns it as {@code MIDENT}, enters user mode and returns
 * to idle after the program's {@code ECALL}. The router keeps one
 * {@link MachineRuntime} and boots it once per reload, so the kernel is reused
 * across messages. Program RAM, including its data and bss, persists between
 * units; several programs, per-unit program isolation and arbitrary load
 * addresses are still pending V4 work.</p>
 *
 * <p>Failures never degrade to a default success. A faulted guest, an exhausted
 * watchdog, a malformed ROM or a policy without a decision produce
 * {@link PolicyTarget#REJECT} with an explicit reason. After a guest failure the
 * machine is not reused: every later route reports {@code RESTART_REQUIRED}
 * until {@link #reload()} succeeds.</p>
 */
public final class Iflow32Router implements Router, AutoCloseable {

    /** ROM file expected in the engine configuration directory. */
    public static final String POLICY_FILE = "policy.flow";

    /** RAM of the VM: the ADR-037 default of 64 MiB (sparse frames). */
    public static final int RAM_SIZE = 64 * 1024 * 1024;

    /**
     * Handle used by the current guest kernel. The loader slice assigns
     * {@code MIDENT = 1} to the single open unit; the router serializes routing,
     * so the handle is unambiguous.
     */
    public static final int MESSAGE_HANDLE = 1;

    private static final int MAX_SLICES = 4_000;

    private final Path configDir;
    private final ChannelRegistry channels;
    private final PermissionChecker permissions;
    private final PluginLogger logger;
    private final RateLimiter rateLimiter = new RateLimiter();
    private final Object machineLock = new Object();

    private Supplier<SyncBus> syncBus = () -> null;

    private RomImage rom;
    private Set<String> capabilities = Set.of();
    private String programSha256;
    private String lastError = "policy.flow has not been loaded";
    private CoreCountConfig coreCount = CoreCountConfig.defaults();
    private WatchdogConfig watchdog = WatchdogConfig.defaults();

    private MachineRuntime runtime;
    private HostAbi host;
    private V3HostBridge bridge;
    private boolean booted;
    private int boots;
    private String machineFailure;
    private boolean closed;

    public Iflow32Router(Path configDir,
                         ChannelRegistry channels,
                         PermissionChecker permissions,
                         PluginLogger logger) {
        this.configDir = Objects.requireNonNull(configDir, "configDir")
                .toAbsolutePath().normalize();
        this.channels = Objects.requireNonNull(channels, "channels");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.logger = Objects.requireNonNull(logger, "logger");
        load();
    }

    /**
     * Resolves the sync bus on every call. It must be configured before the
     * first message: the SYSINFO feature bits are fixed when the machine is
     * created.
     */
    public void syncBus(Supplier<SyncBus> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        synchronized (machineLock) {
            if (runtime != null) {
                throw new IllegalStateException(
                        "sync bus must be configured before the first message");
            }
            this.syncBus = supplier;
        }
    }

    /** @return {@code true} when a validated ROM is loaded and the VM can run. */
    public boolean isAvailable() {
        return rom != null && !closed;
    }

    /** @return last load/verification error, or an empty string when available. */
    public String lastError() {
        return lastError;
    }

    /** @return SHA-256 of the loaded ROM, or {@code null} when unavailable. */
    public String policySha256() {
        return programSha256;
    }

    /** @return times the guest kernel was booted since the last reload. */
    public int boots() {
        synchronized (machineLock) {
            return boots;
        }
    }

    /** Reloads {@code policy.flow} and discards the current machine. */
    public boolean reload() {
        synchronized (machineLock) {
            discardMachine();
            load();
            return rom != null;
        }
    }

    @Override
    public RouteOutcome route(Message message, Actor recipient) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(recipient, "recipient");
        Actor emitter = message.sender() == null ? Actor.unknown("console") : message.sender();
        String path = message.channel() == null || message.channel().isBlank()
                ? "chat" : message.channel();
        Channel channel = channels.resolve(path);

        if (!recipient.equals(emitter) && !allowed(emitter, channel.sendPolicy())) {
            return outcome(PolicyTarget.REJECT,
                    "emitter lacks send permission on " + path, recipient, emitter, message);
        }
        if (!allowed(recipient, channel.receivePolicy())) {
            return outcome(PolicyTarget.DROP,
                    "recipient lacks receive permission on " + path, recipient, emitter, message);
        }
        if (closed) {
            return outcome(PolicyTarget.REJECT,
                    "iflow-32 is closed", recipient, emitter, message);
        }
        if (rom == null) {
            return outcome(PolicyTarget.REJECT,
                    "iflow-32 unavailable: " + lastError, recipient, emitter, message);
        }

        synchronized (machineLock) {
            if (machineFailure != null) {
                return outcome(PolicyTarget.REJECT,
                        "iflow-32 requires restart: " + machineFailure,
                        recipient, emitter, message);
            }
            try {
                ensureMachine();
                if (!booted) {
                    runUntilIdle();
                    booted = true;
                    boots++;
                }

                bridge.open(MESSAGE_HANDLE, message, emitter, recipient);
                boolean committed = false;
                try {
                    host.beginExecution(MESSAGE_HANDLE);
                    runtime.memory().mailbox().publish(
                            HostMailbox.TYPE_MESSAGE, MESSAGE_HANDLE);
                    runUntilIdle();
                    host.commitExecution(MESSAGE_HANDLE);
                    committed = true;
                } finally {
                    if (!committed && host.isExecutionOpen(MESSAGE_HANDLE)) {
                        host.abortExecution(MESSAGE_HANDLE);
                    }
                }

                V3HostBridge.Decision decision =
                        bridge.decision(MESSAGE_HANDLE).orElse(null);
                Message routed = bridge.message(MESSAGE_HANDLE).orElse(message);
                bridge.close(MESSAGE_HANDLE);

                if (decision == null) {
                    return outcome(PolicyTarget.REJECT,
                            "iflow-32 policy produced no decision",
                            recipient, emitter, routed);
                }
                PolicyTarget target = switch (decision.target()) {
                    case HostAbi.TARGET_DELIVER -> PolicyTarget.LOG;
                    case HostAbi.TARGET_CANCEL_EVENT -> PolicyTarget.DROP;
                    default -> PolicyTarget.REJECT;
                };
                String reason = decision.reason();
                if (reason == null || reason.isBlank()) {
                    reason = decision.error();
                }
                if (reason == null || reason.isBlank()) {
                    reason = "iflow-32 " + target.name().toLowerCase(java.util.Locale.ROOT);
                }
                return outcome(target, reason, recipient, emitter, routed);
            } catch (HostServiceException failure) {
                if (bridge != null) {
                    bridge.close(MESSAGE_HANDLE);
                }
                return outcome(PolicyTarget.REJECT,
                        "iflow-32 internal error: " + message(failure),
                        recipient, emitter, message);
            } catch (RuntimeException failure) {
                if (bridge != null) {
                    bridge.close(MESSAGE_HANDLE);
                }
                machineFailure = message(failure);
                booted = false;
                return outcome(PolicyTarget.REJECT,
                        "iflow-32 requires restart: " + machineFailure,
                        recipient, emitter, message);
            }
        }
    }

    @Override
    public boolean hasPermission(Actor actor, String permission) {
        return permissions.has(actor, permission);
    }

    @Override
    public boolean checkEmissionRateLimit(Message message) {
        Objects.requireNonNull(message, "message");
        String path = message.channel() == null || message.channel().isBlank()
                ? "chat" : message.channel();
        Channel channel = channels.resolve(path);
        Actor emitter = message.sender() == null ? Actor.unknown("console") : message.sender();
        if (channel.rateLimitPerSecond() > 0) {
            String key = path + "\u0000" + emitter.uuid();
            return rateLimiter.tryAcquire(key, channel.rateLimitPerSecond());
        }
        return true;
    }

    @Override
    public long nanosUntilNextRateLimitWindow(Message message) {
        Objects.requireNonNull(message, "message");
        String path = message.channel() == null || message.channel().isBlank()
                ? "chat" : message.channel();
        Channel channel = channels.resolve(path);
        if (channel.rateLimitPerSecond() > 0) {
            Actor emitter = message.sender() == null
                    ? Actor.unknown("console") : message.sender();
            return rateLimiter.nanosUntilNextWindow(path + "\u0000" + emitter.uuid());
        }
        return 0;
    }

    @Override
    public void close() {
        synchronized (machineLock) {
            if (closed) {
                return;
            }
            closed = true;
            discardMachine();
            rom = null;
            lastError = "router is closed";
        }
        rateLimiter.close();
    }

    private void load() {
        try {
            this.coreCount = CoreCountConfig.load(configDir);
            this.watchdog = WatchdogConfig.load(configDir, logger);
        } catch (RuntimeException failure) {
            unavailable("invalid configuration in " + configDir + ": " + message(failure));
            return;
        }
        Path file = configDir.resolve(POLICY_FILE);
        if (!Files.isRegularFile(file)) {
            unavailable("missing " + POLICY_FILE + " in " + configDir);
            return;
        }
        try {
            RomImage image = RomParser.parse(Files.readAllBytes(file));
            if (image.programs().size() != 1) {
                unavailable("policy.flow must contain exactly one PROGRAM; found "
                        + image.programs().size());
                return;
            }
            this.rom = image;
            this.capabilities = Set.copyOf(image.programs().get(0).capabilities());
            this.programSha256 = sha256(image.bytes());
            this.lastError = "";
            logger.info("iFlow-32 policy loaded: {} (sha256 {})",
                    file, this.programSha256);
        } catch (IOException | RuntimeException failure) {
            unavailable("invalid " + POLICY_FILE + " in " + configDir + ": "
                    + message(failure));
        }
    }

    private void ensureMachine() {
        if (runtime != null) {
            return;
        }
        if (rom == null) {
            throw new IllegalStateException("iflow-32 is unavailable: " + lastError);
        }
        VirtualClock clock = new VirtualClock();
        MachineMemory memory = MachineMemory.fromRom(new CsrFile(0), rom, RAM_SIZE,
                clock, syncBus.get() != null, coreCount.coreCount());
        this.bridge = new V3HostBridge(channels, permissions, syncBus,
                programSha256, capabilities);
        this.host = new HostAbi(bridge);
        HostAbi abi = this.host;
        this.runtime = new MachineRuntime(memory, clock, rom.header().resetVector(),
                ExecutionQuantum.defaults(), watchdog, ignored -> abi, System::nanoTime);
        this.booted = false;
        this.machineFailure = null;
    }

    /**
     * Runs every core until all of them are idle in {@code HALT}. A pending
     * mailbox IRQ wakes the halted core inside {@code runCore}, so halted cores
     * are executed instead of skipped; the loop ends only when every core is
     * halted again after at least one round.
     */
    private void runUntilIdle() {
        for (int slice = 0; slice < MAX_SLICES; slice++) {
            for (int core = 0; core < runtime.coreCount(); core++) {
                ExecutionReport report = runtime.runCore(core);
                if (report.status() == ExecutionStatus.WATCHDOG) {
                    throw new IllegalStateException(
                            "watchdog expired on COREID " + core);
                }
            }
            if (runtime.faulted()) {
                throw new IllegalStateException("guest faulted");
            }
            boolean allHalted = true;
            for (int core = 0; core < runtime.coreCount(); core++) {
                if (!runtime.core(core).cpu().halted()) {
                    allHalted = false;
                    break;
                }
            }
            if (allHalted) {
                return;
            }
        }
        throw new IllegalStateException(
                "guest did not become idle within " + MAX_SLICES + " slices");
    }

    private void discardMachine() {
        runtime = null;
        host = null;
        bridge = null;
        booted = false;
        boots = 0;
        machineFailure = null;
    }

    private void unavailable(String reason) {
        rom = null;
        capabilities = Set.of();
        programSha256 = null;
        lastError = reason;
        logger.warn("iFlow-32 unavailable: {}", reason);
    }

    private boolean allowed(Actor actor, String permission) {
        return permission == null || permission.isBlank()
                || permissions.has(actor, permission);
    }

    private static RouteOutcome outcome(PolicyTarget target, String reason,
                                        Actor recipient, Actor emitter, Message message) {
        return RouteOutcome.of(new RouteDecision(
                target, reason == null ? "" : reason, 0, recipient, emitter), message);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 not available", failure);
        }
    }

    private static String message(Throwable failure) {
        String text = failure.getMessage();
        return text == null || text.isBlank()
                ? failure.getClass().getSimpleName() : text;
    }
}

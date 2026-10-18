package me.majhrs16.suite.iflow.vm.engine;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.iflow.Router;
import me.majhrs16.suite.iflow.RouteDecision;
import me.majhrs16.suite.iflow.RouteOutcome;
import me.majhrs16.suite.iflow.channel.PermissionChecker;
import me.majhrs16.suite.iflow.channel.RateLimiter;
import me.majhrs16.suite.iflow.target.PolicyTarget;
import me.majhrs16.suite.textformatter.channel.Channel;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;
import me.majhrs16.suite.logger.Logger;
import me.majhrs16.suite.iflow.vm.engine.PolicyResultValidator;
import me.majhrs16.suite.iflow.vm.execution.ExecutionContext;
import me.majhrs16.suite.iflow.vm.execution.ExecutionContextPool;
import me.majhrs16.suite.iflow.vm.execution.ModificationJournal;
import me.majhrs16.suite.iflow.vm.execution.VmInterpreter;
import me.majhrs16.suite.iflow.vm.PassThroughProgram;
import me.majhrs16.suite.iflow.vm.isa.ProgramImage;
import me.majhrs16.suite.iflow.vm.isa.VmException;
import me.majhrs16.suite.iflow.vm.memory.PageEntry;
import me.majhrs16.suite.iflow.vm.memory.PageTable;
import me.majhrs16.suite.iflow.vm.memory.SoftMMU;
import me.majhrs16.suite.iflow.vm.memory.VmBus;
import me.majhrs16.suite.iflow.vm.memory.VmMemoryMap;
import me.majhrs16.suite.iflow.vm.memory.VmNvram;
import me.majhrs16.suite.iflow.vm.service.HostContext;
import me.majhrs16.suite.iflow.vm.service.HostServiceRegistry;
import me.majhrs16.suite.iflow.vm.video.VmDisplay;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.charset.StandardCharsets;

/**
 * VM-based policy engine implementing the Router interface.
 * <p>
 * Executes pre-compiled VM bytecode programs (.flow files).
 * No YAML, no compilation. Pure bytecode execution.
 * Uses flat 64KB address space with SoftMMU (4KB paging).
 * </p>
 */
public final class VmPolicyEngine implements Router, AutoCloseable {

    private final ChannelRegistry channels;
    private final PermissionChecker permissions;
    private final RateLimiter rateLimit;
    private final AtomicReference<LoadedProgram> loadedProgramRef = new AtomicReference<>();
    private final HostServiceRegistry serviceRegistry;
    private final ExecutionContextPool contextPool;
    private final PolicyResultValidator validator;

    /**
     * Persistent state exposed to policies through {@code STATE_READ}/{@code STATE_WRITE}
     * (the host side of {@link HostContext}). It survives across evaluations and is
     * keyed by the 16-bit key the program passes in {@code options}.
     */
    private final Map<Integer, Object> hostState = new ConcurrentHashMap<>();

    /**
     * Serializes executions on the program's single physical machine (one core).
     * The machine memory is long-lived and mutated in place (see {@code route}),
     * so two messages must not interleave their execution.
     */
    private final java.util.concurrent.locks.ReentrantLock machineLock =
        new java.util.concurrent.locks.ReentrantLock();

    // Configuration
    private final long defaultFuelBudget = 100_000; // 100K fuel units per evaluation

    // Standard memory layout is defined once in VmMemoryMap; the engine only
    // consumes it. See that class for the page map.

    /**
     * Immutable container for a loaded program: bytecode + initialized SoftMMU +
     * the per-program interpreter. Published atomically to ensure program, memory
     * and interpreter are always in sync.
     */
    public static final class LoadedProgram {
        public final ProgramImage program;
        /**
         * The machine's live memory: mapped once at boot and then mutated in place
         * by every execution, so program data persists across messages (D30). It is
         * not a per-execution fork.
         */
        public final SoftMMU softMMU;
        /**
         * The machine's text screen (D31): a memory-mapped peripheral attached to
         * {@link #softMMU}. It is the same object across messages (the machine is
         * long-lived), so what a program draws stays on screen.
         */
        public final VmDisplay display;
        /**
         * The machine's BIOS NVRAM (D32): a small persistent scratch window the
         * reset routine uses for state that must survive across messages (the
         * cold-boot flag). Same instance across messages, like {@link #display}.
         */
        public final VmNvram nvram;
        /**
         * The interpreter is immutable and stateless (it reads the bus/MMU from the
         * execution context), so one instance serves every execution of this program
         * (IFLOW-AUD-029).
         */
        public final VmInterpreter interpreter;

        public LoadedProgram(ProgramImage program, SoftMMU softMMU, VmDisplay display,
                             VmNvram nvram, VmInterpreter interpreter) {
            this.program = program;
            this.softMMU = softMMU;
            this.display = display;
            this.nvram = nvram;
            this.interpreter = interpreter;
        }
    }

    public VmPolicyEngine(ChannelRegistry channels,
                          PermissionChecker permissions,
                          RateLimiter rateLimit) {
        this.channels = channels;
        this.permissions = permissions;
        this.rateLimit = rateLimit;

        this.serviceRegistry = createServiceRegistry();
        this.contextPool = new ExecutionContextPool(serviceRegistry, this::configureContext);
        this.validator = new PolicyResultValidator(channels);

        // Initialize with empty program (pass-through)
        setProgram(PassThroughProgram.INSTANCE);
    }

    private HostServiceRegistry createServiceRegistry() {
        HostServiceRegistry registry = new HostServiceRegistry();

        // Declare standard capabilities
        registry.declareCapability("property.read");
        registry.declareCapability("property.write");
        registry.declareCapability("permission.check");
        registry.declareCapability("routing.decide");
        registry.declareCapability("state.read");
        registry.declareCapability("state.write");
        registry.declareCapability("channel.resolve");

        // Inject host dependencies. The message/property/state/channel views are
        // supplied per execution through the HostContext port (see route()),
        // not as engine-level lambdas.
        registry.permissionChecker((actorName, perm) -> permissions.has(
            Actor.unknown(actorName), perm));
        registry.rateLimiter(rateLimit);

        return registry;
    }

    private void configureContext(ExecutionContext ctx) {
        // Set standard configuration
        ctx.fuelBudget(defaultFuelBudget);
    }

    @Override
    public RouteOutcome route(Message message, Actor recipient) {
        Actor emitter = message.sender();
        String path = message.channel() == null ? "chat" : message.channel();

        // Check channel send policy (emitter)
        if (!recipient.equals(emitter) && !allowed(emitter, channels.resolve(path).sendPolicy())) {
            return RouteOutcome.of(new RouteDecision(PolicyTarget.REJECT,
                "emitter lacks send permission on " + path, 0, recipient, emitter), message);
        }

        // Check channel receive policy (recipient)
        if (!allowed(recipient, channels.resolve(path).receivePolicy())) {
            return RouteOutcome.of(new RouteDecision(PolicyTarget.DROP,
                "recipient lacks receive permission on " + path, 0, recipient, emitter), message);
        }

        // One physical machine per program: executions are serialized on the
        // single core, so the long-lived memory below needs neither a per-message
        // fork nor locking inside the MMU. This is what makes the VM instance
        // persist across messages: a counter a program leaves at 0x9000+ is still
        // there on the next message, because the runtime never clears it.
        machineLock.lock();
        try {
            // Get current loaded program (program + live machine memory)
            LoadedProgram loaded = loadedProgramRef.get();
            if (loaded == null) {
                throw new IllegalStateException("No program loaded");
            }

            // Acquire execution context from pool
            ExecutionContext ctx = contextPool.acquire();
            try {
                // Configure context for this evaluation. The MMU is the machine's
                // live memory, shared by every message (no fork): writes land in
                // the program's own RAM and stay there.
                ctx.executionId(java.util.UUID.randomUUID().toString())
                   .programId(loaded.program.hashCode() + "")
                   .messageId(message.id().toString())
                   .actorId(recipient.name())
                   .softMMU(loaded.softMMU)
                   .serviceRegistry(serviceRegistry)
                   .allowedCapabilities(grantedCapabilities(
                       loaded.program.capabilities(), serviceRegistry.declaredCapabilities()));

                // Initialize context (resets registers, fuel, clears I/O buffers)
                ctx.initialize();

                // Install the host view for this message: property reads, channel
                // resolution and persistent state now go through the HostContext port.
                ctx.hostContext(new MessageHostContext(ctx, message, emitter, recipient, path));

                // Prepare input: serialize message to the input buffer (see VmMemoryMap)
                prepareInput(ctx, message, emitter, recipient, path);

                // Execute VM (the per-program interpreter is immutable and reused)
                loaded.interpreter.execute(ctx);

                // Validate and produce outcome. In debug builds, dump the text
                // screen (D31) so a policy's output is visible on the console;
                // the supplier is never called when debug is off.
                Logger.debug(() -> "iflow screen:\n" + loaded.display.renderTextTrimmed());
                return validator.validate(ctx, message, recipient, emitter);

            } catch (VmException e) {
                // VM execution error - reject
                return RouteOutcome.of(new RouteDecision(PolicyTarget.REJECT,
                    "VM error: " + e.getMessage(), 0, recipient, emitter), message);
            } catch (RuntimeException e) {
                // Safety net for the message path: an unexpected runtime failure must
                // never escape the router (it would propagate to the server's chat
                // pipeline). Reject the message instead.
                return RouteOutcome.of(new RouteDecision(PolicyTarget.REJECT,
                    "VM internal error: " + e.getMessage(), 0, recipient, emitter), message);
            } finally {
                contextPool.release(ctx);
            }
        } finally {
            machineLock.unlock();
        }
    }

    /**
     * Prepares a SoftMMU with program mappings (code, constants).
     * Called when program is loaded/changed.
     */
    private void setupProgramMappings(ProgramImage program, SoftMMU mmu) {
        PageTable pageTable = mmu.getPageTable();
        // Boot-time loads are bus transactions too (D18): the host writes the
        // image through the same interconnection the CPU and DMA use. The loader
        // is a supervisor master, so it may write kernel-owned pages (design.md §14).
        VmBus bus = VmBus.supervisor(mmu);

        // Code region: up to CODE_PAGES pages (firmware ROM page 0 + user code
        // page 1). The region is reserved in full so code never aliases the
        // constants page, regardless of whether firmware is enabled. It is mapped
        // as kernel-owned (R/W/X) while the image is written, then demoted to R/X
        // with mprotect: the program can never write it, not even during boot.
        byte[] code = program.code();
        int codePages = (code.length + VmMemoryMap.PAGE - 1) / VmMemoryMap.PAGE;
        if (codePages > VmMemoryMap.CODE_PAGES) {
            throw new IllegalArgumentException(
                "Program code too large: " + code.length + " bytes (" + codePages + " pages), " +
                "maximum is " + VmMemoryMap.CODE_MAX_SIZE + " bytes (" + VmMemoryMap.CODE_PAGES +
                " pages: firmware ROM + user code)."
            );
        }
        for (int p = 0; p < codePages; p++) {
            int vaddrPage = VmMemoryMap.pageOf(VmMemoryMap.CODE_BASE) + p;
            int frameBase = VmMemoryMap.CODE_BASE + p * VmMemoryMap.PAGE; // Identity mapping
            pageTable.mapPageKernel(vaddrPage, frameBase,
                PageEntry.PERM_READ | PageEntry.PERM_WRITE | PageEntry.PERM_EXECUTE);
        }

        // Copy code into physical memory
        bus.writeBlock(VmMemoryMap.CODE_BASE, code, 0, code.length);

        // Demote code pages to R/X (mprotect: remove write after writing)
        for (int p = 0; p < codePages; p++) {
            int vaddrPage = VmMemoryMap.pageOf(VmMemoryMap.CODE_BASE) + p;
            pageTable.protect(vaddrPage, PageEntry.PERM_READ | PageEntry.PERM_EXECUTE);
        }

        // Map the constants page - kernel-owned R/W while writing, then R
        if (program.constants().length > 0) {
            byte[] constBytes = new byte[program.constants().length * 2];
            for (int i = 0; i < program.constants().length; i++) {
                int val = program.constants()[i];
                constBytes[i * 2] = (byte) (val & 0xFF);
                constBytes[i * 2 + 1] = (byte) ((val >> 8) & 0xFF);
            }
            int constPage = VmMemoryMap.pageOf(VmMemoryMap.CONSTANTS_BASE);
            pageTable.mapPageKernel(constPage, VmMemoryMap.CONSTANTS_BASE,
                PageEntry.PERM_READ | PageEntry.PERM_WRITE);
            bus.writeBlock(VmMemoryMap.CONSTANTS_BASE, constBytes, 0, constBytes.length);
            // Read-only for the program after writing (mprotect)
            pageTable.protect(constPage, PageEntry.PERM_READ);
        }

        // Map data pages - R/W
        for (int p = 0; p < VmMemoryMap.DATA_PAGES; p++) {
            int page = VmMemoryMap.pageOf(VmMemoryMap.DATA_BASE) + p;
            pageTable.mapPage(page, VmMemoryMap.DATA_BASE + p * VmMemoryMap.PAGE,
                PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        }

        // Map stack pages - R/W
        for (int p = 0; p < VmMemoryMap.STACK_PAGES; p++) {
            int page = VmMemoryMap.pageOf(VmMemoryMap.STACK_BASE) + p;
            pageTable.mapPage(page, VmMemoryMap.STACK_BASE + p * VmMemoryMap.PAGE,
                PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        }

        // Map I/O buffer pages - R/W
        pageTable.mapPage(VmMemoryMap.pageOf(VmMemoryMap.INPUT_BASE), VmMemoryMap.INPUT_BASE,
            PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        pageTable.mapPage(VmMemoryMap.pageOf(VmMemoryMap.OUTPUT_BASE), VmMemoryMap.OUTPUT_BASE,
            PageEntry.PERM_READ | PageEntry.PERM_WRITE);

        // Map the general-purpose user RAM - R/W. This is plain memory the
        // program owns; it is not a device and is never cleared by the runtime.
        for (int p = 0; p < VmMemoryMap.USER_PAGES; p++) {
            int page = VmMemoryMap.pageOf(VmMemoryMap.USER_BASE) + p;
            pageTable.mapPage(page, VmMemoryMap.USER_BASE + p * VmMemoryMap.PAGE,
                PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        }
    }

    private void prepareInput(ExecutionContext ctx, Message message, Actor emitter,
                              Actor recipient, String channelPath) {
        // Write message properties to the input buffer (see VmMemoryMap). Every
        // field is clamped to the space that is actually left, so a message whose
        // UTF-8 size exceeds the input page degrades gracefully instead of
        // producing a negative System.arraycopy length (IndexOutOfBoundsException).
        int inputVAddr = ctx.inputBufferVAddr();
        int size = ctx.ioBufferSize();
        int offset = 0;

        offset = writeField(ctx, inputVAddr, offset, size,
            message.text().getBytes(StandardCharsets.UTF_8), 256); // keep room for the rest
        offset = writeField(ctx, inputVAddr, offset, size,
            channelPath.getBytes(StandardCharsets.UTF_8), 1);
        offset = writeField(ctx, inputVAddr, offset, size,
            message.direction().toString().getBytes(StandardCharsets.UTF_8), 1);
        offset = writeField(ctx, inputVAddr, offset, size,
            emitter.name().getBytes(StandardCharsets.UTF_8), 1);
        writeField(ctx, inputVAddr, offset, size,
            recipient.name().getBytes(StandardCharsets.UTF_8), 1);
    }

    /**
     * Writes one NUL-terminated field into the input buffer, reserving {@code keep}
     * bytes for the fields that follow.
     *
     * @return the offset after the field (and its terminator, when it fits)
     */
    private static int writeField(ExecutionContext ctx, int base, int offset, int size,
                                  byte[] bytes, int keep) {
        int room = Math.max(0, size - offset - keep);
        int len = Math.min(bytes.length, room);
        if (len > 0) {
            ctx.dma().writeBlock(base + offset, bytes, 0, len);
            offset += len;
        }
        if (offset < size) {
            ctx.dma().writeByte(base + offset, 0);
            offset++;
        }
        return offset;
    }

    private boolean allowed(Actor actor, String permission) {
        return permission == null || permissions.has(actor, permission);
    }

    // ===== Host port implementation (IFLOW-AUD-016) =====

    /**
     * Read/write view of the current message, its actors, the channel and the
     * engine's persistent state. Installed on the execution context for the
     * duration of one evaluation, so the HOST services no longer depend on
     * engine-level lambdas.
     */
    private final class MessageHostContext implements HostContext {
        private final ExecutionContext ctx;
        private final Message message;
        private final Actor emitter;
        private final Actor recipient;
        private final String path;

        MessageHostContext(ExecutionContext ctx, Message message, Actor emitter,
                           Actor recipient, String path) {
            this.ctx = ctx;
            this.message = message;
            this.emitter = emitter;
            this.recipient = recipient;
            this.path = path;
        }

        @Override
        public Optional<String> readProperty(int scope, int propId) {
            return Optional.ofNullable(switch (scope) {
                case HostServiceRegistry.SCOPE_MESSAGE -> messageProperty(propId);
                case HostServiceRegistry.SCOPE_EMITTER -> actorProperty(emitter, propId);
                case HostServiceRegistry.SCOPE_RECIPIENT -> actorProperty(recipient, propId);
                case HostServiceRegistry.SCOPE_CHANNEL -> channelProperty(propId);
                default -> null;
            });
        }

        @Override
        public boolean writeProperty(int scope, int propId, String value) {
            if (value == null) {
                return false;
            }
            // Message mutation is deferred: journal the edit that matches the
            // requested (scope, propId), which the engine validates and commits
            // after execution. Unknown pairs are rejected (AUD-028).
            ModificationJournal.Entry entry = switch (scope) {
                case HostServiceRegistry.SCOPE_MESSAGE -> switch (propId) {
                    case HostServiceRegistry.PROP_CONTENT -> new ModificationJournal.Entry(
                        ModificationJournal.OpType.SET_TEXT, value, "propWrite:" + propId);
                    case HostServiceRegistry.PROP_CHANNEL -> new ModificationJournal.Entry(
                        ModificationJournal.OpType.SET_CHANNEL, value, "propWrite:" + propId);
                    case HostServiceRegistry.PROP_LANG_SOURCE -> new ModificationJournal.Entry(
                        ModificationJournal.OpType.SET_LANG_SOURCE, value, "propWrite:" + propId);
                    case HostServiceRegistry.PROP_LANG_TARGET -> new ModificationJournal.Entry(
                        ModificationJournal.OpType.SET_LANG_TARGET, value, "propWrite:" + propId);
                    case HostServiceRegistry.PROP_COLOR_MODE -> new ModificationJournal.Entry(
                        ModificationJournal.OpType.SET_COLOR_MODE, value, "propWrite:" + propId);
                    default -> null;
                };
                case HostServiceRegistry.SCOPE_CHANNEL ->
                    propId == HostServiceRegistry.PROP_CHAN_NAME ? new ModificationJournal.Entry(
                        ModificationJournal.OpType.SET_CHANNEL, value, "propWrite:" + propId) : null;
                default -> null;
            };
            if (entry == null) {
                return false;
            }
            ctx.journal().record(entry);
            return true;
        }

        @Override
        public Optional<String> resolveChannel(String name) {
            if (name == null) {
                return Optional.empty();
            }
            return channels.get(name).map(ch -> ch.name());
        }

        @Override
        public Optional<String> readChannelProperty(String channelName, int propId) {
            if (channelName == null) {
                return Optional.empty();
            }
            return channels.get(channelName)
                .map(ch -> channelProperty(ch, ch.name(), propId));
        }

        @Override
        public Optional<Object> readState(int key) {
            return Optional.ofNullable(hostState.get(key));
        }

        @Override
        public void writeState(int key, Object value) {
            if (value != null) {
                hostState.put(key, value);
            }
        }

        private String messageProperty(int propId) {
            return switch (propId) {
                case HostServiceRegistry.PROP_CONTENT -> message.text();
                case HostServiceRegistry.PROP_CHANNEL -> path;
                case HostServiceRegistry.PROP_DIRECTION -> message.direction().toString();
                case HostServiceRegistry.PROP_TYPE -> message.type().toString();
                case HostServiceRegistry.PROP_LANG_SOURCE -> String.valueOf(message.langSource());
                case HostServiceRegistry.PROP_LANG_TARGET -> String.valueOf(message.langTarget());
                case HostServiceRegistry.PROP_COLOR_MODE -> String.valueOf(message.colorMode());
                case HostServiceRegistry.PROP_FORMAT_PAPI -> String.valueOf(message.formatPapi());
                case HostServiceRegistry.PROP_SLEEP_MILLIS -> String.valueOf(message.sleepMillis());
                case HostServiceRegistry.PROP_CANCELLED -> String.valueOf(message.isCancelled());
                default -> null;
            };
        }

        private String actorProperty(Actor actor, int propId) {
            if (actor == null) {
                return null;
            }
            return switch (propId) {
                case HostServiceRegistry.PROP_ACTOR_NAME -> actor.name();
                case HostServiceRegistry.PROP_ACTOR_UUID -> String.valueOf(actor.uuid());
                case HostServiceRegistry.PROP_ACTOR_LANG -> String.valueOf(actor.language());
                default -> null;
            };
        }

        private String channelProperty(int propId) {
            return channelProperty(channels.resolve(path), path, propId);
        }

        private String channelProperty(Channel channel, String name, int propId) {
            return switch (propId) {
                case HostServiceRegistry.PROP_CHAN_NAME -> name;
                case HostServiceRegistry.PROP_CHAN_RATE ->
                    String.valueOf(channel.rateLimitPerSecond());
                case HostServiceRegistry.PROP_CHAN_SEND_POL -> channel.sendPolicy();
                case HostServiceRegistry.PROP_CHAN_RECV_POL -> channel.receivePolicy();
                default -> null;
            };
        }
    }

    /**
     * Intersects the capabilities a program <em>requests</em> (its
     * {@code .capability} directives) with the capabilities the host
     * <em>grants</em>. The program can never widen its own privileges: a
     * capability the host has not declared is dropped.
     */
    static String[] grantedCapabilities(String[] requested, Set<String> granted) {
        List<String> effective = new ArrayList<>(requested.length);
        for (String cap : requested) {
            if (granted.contains(cap)) {
                effective.add(cap);
            }
        }
        return effective.toArray(new String[0]);
    }

    @Override
    public boolean hasPermission(Actor actor, String permission) {
        return allowed(actor, permission);
    }

    /**
     * @throws UnsupportedOperationException always. iflow VM executes binary programs only.
     * Use {@link #setProgram(ProgramImage)} for hot-reload of .flow files.
     */
    @Override
    public void setRules(Collection<?> rules) {
        throw new UnsupportedOperationException("iflow VM executes binary .flow programs only. " +
            "Use setProgram(ProgramImage) for hot-reload, or compile rules externally via web editor.");
    }

    @Override
    public Collection<?> rules() {
        return Collections.emptyList();
    }

    /**
     * Sets the VM program (hot-reload).
     * This replaces setRules for VM-based policies.
     * Program and memory mappings are published atomically.
     */
    public void setProgram(ProgramImage program) {
        Objects.requireNonNull(program, "program");
        // Create the machine's memory and lay the program out on it (boot).
        SoftMMU newMMU = new SoftMMU();
        setupProgramMappings(program, newMMU);
        // Publish the boot image (code/constants) as an immutable snapshot. The
        // machine memory is then mutated in place across messages (D30), so the
        // program's data survives from one message to the next.
        newMMU.freeze();
        // Attach the text screen (D31): a memory-mapped peripheral the BIOS will
        // configure at boot. It starts disabled so it decodes nothing until the
        // BIOS enables it.
        VmDisplay display = new VmDisplay();
        newMMU.attach(display);
        // Attach the BIOS NVRAM (D32): persistent scratch the reset routine uses
        // to tell a cold boot from a warm one (see VmNvram).
        VmNvram nvram = new VmNvram();
        newMMU.attach(nvram);
        // The interpreter is stateless (bus/MMU come from the execution context) and
        // the program is immutable, so build it once per program, not per message.
        VmInterpreter interpreter = new VmInterpreter(program, serviceRegistry);
        // Atomically publish program + memory + display + interpreter together
        loadedProgramRef.set(new LoadedProgram(program, newMMU, display, nvram, interpreter));
    }

    /** The current machine's text screen (D31), or {@code null} before a program loads. */
    public VmDisplay display() {
        LoadedProgram loaded = loadedProgramRef.get();
        return loaded != null ? loaded.display : null;
    }

    /** The current machine's BIOS NVRAM (D32), or {@code null} before a program loads. */
    public VmNvram nvram() {
        LoadedProgram loaded = loadedProgramRef.get();
        return loaded != null ? loaded.nvram : null;
    }

    public ProgramImage program() {
        LoadedProgram loaded = loadedProgramRef.get();
        return loaded != null ? loaded.program : null;
    }

    @Override
    public boolean checkEmissionRateLimit(Message message) {
        String path = message.channel() == null ? "chat" : message.channel();
        var channel = channels.resolve(path);
        Actor emitter = message.sender();

        if (channel.rateLimitPerSecond() > 0) {
            String key = path + "\u0000" + emitter.uuid();
            return rateLimit.tryAcquire(key, channel.rateLimitPerSecond());
        }
        return true;
    }

    @Override
    public long nanosUntilNextRateLimitWindow(Message message) {
        String path = message.channel() == null ? "chat" : message.channel();
        var channel = channels.resolve(path);
        Actor emitter = message.sender();

        if (channel.rateLimitPerSecond() > 0) {
            String key = path + "\u0000" + emitter.uuid();
            return rateLimit.nanosUntilNextWindow(key);
        }
        return 0;
    }

    @Override
    public void close() {
        contextPool.close();
        if (rateLimit != null) {
            rateLimit.close();
        }
    }
}
package me.majhrs16.suite.iflow.vm.engine;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.iflow.Router;
import me.majhrs16.suite.iflow.RouteDecision;
import me.majhrs16.suite.iflow.RouteOutcome;
import me.majhrs16.suite.iflow.channel.PermissionChecker;
import me.majhrs16.suite.iflow.channel.RateLimiter;
import me.majhrs16.suite.iflow.target.PolicyTarget;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;
import me.majhrs16.suite.iflow.vm.engine.PolicyResultValidator;
import me.majhrs16.suite.iflow.vm.execution.ExecutionContext;
import me.majhrs16.suite.iflow.vm.execution.ExecutionContextPool;
import me.majhrs16.suite.iflow.vm.execution.VmInterpreter;
import me.majhrs16.suite.iflow.vm.isa.Opcode;
import me.majhrs16.suite.iflow.vm.isa.ProgramImage;
import me.majhrs16.suite.iflow.vm.isa.VmException;
import me.majhrs16.suite.iflow.vm.memory.PageEntry;
import me.majhrs16.suite.iflow.vm.memory.PageTable;
import me.majhrs16.suite.iflow.vm.memory.SoftMMU;
import me.majhrs16.suite.iflow.vm.service.HostServiceRegistry;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
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

    // Configuration
    private final long defaultFuelBudget = 100_000; // 100K fuel units per evaluation

    // Standard page layout:
    // Page 0 (0x0000): Code (R/X)
    // Page 1 (0x1000): Constants (R)
    // Page 2-3 (0x2000-0x3FFF): Data (R/W)
    // Page 4-5 (0x4000-0x5FFF): Stack (R/W) - grows down from 0x6000
    // Page 6 (0x6000): Input buffer (R/W)
    // Page 7 (0x7000): Output buffer (R/W)
    // Page 8-15 (0x8000-0xFFFF): Persistent/Firmware (configurable)

    private static final int CODE_PAGE = 0;
    private static final int CONSTANTS_PAGE = 1;
    private static final int DATA_PAGE_START = 2;
    private static final int STACK_PAGE_START = 4;
    private static final int INPUT_BUFFER_PAGE = 6;
    private static final int OUTPUT_BUFFER_PAGE = 7;

    /**
     * Immutable container for a loaded program: bytecode + initialized SoftMMU.
     * Published atomically to ensure program and memory are always in sync.
     */
    public static final class LoadedProgram {
        public final ProgramImage program;
        public final SoftMMU softMMU;

        public LoadedProgram(ProgramImage program, SoftMMU softMMU) {
            this.program = program;
            this.softMMU = softMMU;
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
        setProgram(createPassThroughProgram());
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

        // Inject host dependencies
        registry.permissionChecker((actorName, perm) -> permissions.has(
            Actor.unknown(actorName), perm));
        registry.rateLimiter(rateLimit);

        return registry;
    }

    private void configureContext(ExecutionContext ctx) {
        // Set standard configuration
        ctx.fuelBudget(defaultFuelBudget);
    }

    private ProgramImage createPassThroughProgram() {
        // Minimal program: call ROUTE_DECISION service with DECISION_CONTINUE
        // HOST convention: R0 = service ID, R3 = options (decision code)
        // HOST instruction is 2 bytes: opcode + padding
        return ProgramImage.builder()
            .entryPoint(0)
            .code(new byte[]{
                Opcode.LI, 0, (HostServiceRegistry.ROUTE_DECISION >> 8) & 0xFF, HostServiceRegistry.ROUTE_DECISION & 0xFF, // LI R0, ROUTE_DECISION
                Opcode.LI, 3, (HostServiceRegistry.DECISION_CONTINUE >> 8) & 0xFF, HostServiceRegistry.DECISION_CONTINUE & 0xFF, // LI R3, DECISION_CONTINUE
                Opcode.HOST, 0x00,                                                    // HOST (2 bytes: opcode + padding)
                Opcode.HALT                                                     // HALT
            })
            .capabilities(new String[]{"routing.decide"})
            .build();
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

        // Acquire execution context from pool
        ExecutionContext ctx = contextPool.acquire();
        try {
            // Get current loaded program (program + memory mappings)
            LoadedProgram loaded = loadedProgramRef.get();
            if (loaded == null) {
                throw new IllegalStateException("No program loaded");
            }

            // Configure context for this evaluation
            ctx.executionId(java.util.UUID.randomUUID().toString())
               .programId(loaded.program().hashCode() + "")
               .messageId(message.id().toString())
               .actorId(recipient.name())
               .softMMU(new SoftMMU(loaded.softMMU()))  // Copy program's MMU (page tables + physical memory)
               .serviceRegistry(serviceRegistry)
               .allowedCapabilities(loaded.program().capabilities());

            // Initialize context (resets registers, fuel, clears I/O buffers)
            ctx.initialize();

            // Prepare input: serialize message to input buffer (page 6: 0x6000)
            prepareInput(ctx, message, emitter, recipient, path);

            // Execute VM
            VmInterpreter interpreter = new VmInterpreter(loaded.program(), ctx.softMMU(), serviceRegistry);
            interpreter.execute(ctx);

            // Validate and produce outcome
            return validator.validate(ctx, message, recipient, emitter);

        } catch (VmException e) {
            // VM execution error - reject
            return RouteOutcome.of(new RouteDecision(PolicyTarget.REJECT,
                "VM error: " + e.getMessage(), 0, recipient, emitter), message);
        } finally {
            contextPool.release(ctx);
        }
    }

    /**
     * Prepares a SoftMMU with program mappings (code, constants).
     * Called when program is loaded/changed.
     */
    private void setupProgramMappings(ProgramImage program, SoftMMU mmu) {
        PageTable pageTable = mmu.getPageTable();

        // Map code page (page 0: 0x0000) - R/W/X initially for writing code, then R/X
        byte[] code = program.code();
        int codePages = (code.length + SoftMMU.PAGE_SIZE - 1) / SoftMMU.PAGE_SIZE;
        for (int p = 0; p < codePages && p < 16; p++) {
            int frameBase = p * SoftMMU.PAGE_SIZE; // Identity mapping for now
            int perms = PageEntry.PERM_READ | PageEntry.PERM_WRITE | PageEntry.PERM_EXECUTE;
            pageTable.mapPage(CODE_PAGE + p, frameBase, perms);
        }

        // Copy code into physical memory
        mmu.writeBlock(0x0000, code, 0, code.length);

        // Change code page to R/X (remove write permission after writing)
        for (int p = 0; p < codePages && p < 16; p++) {
            int frameBase = p * SoftMMU.PAGE_SIZE;
            int perms = PageEntry.PERM_READ | PageEntry.PERM_EXECUTE;
            pageTable.mapPage(CODE_PAGE + p, frameBase, perms);
        }

        // Map constants page (page 1: 0x1000) - R/W initially for writing constants, then R
        if (program.constants().length > 0) {
            byte[] constBytes = new byte[program.constants().length * 2];
            for (int i = 0; i < program.constants().length; i++) {
                int val = program.constants()[i];
                constBytes[i * 2] = (byte) (val & 0xFF);
                constBytes[i * 2 + 1] = (byte) ((val >> 8) & 0xFF);
            }
            pageTable.mapPage(CONSTANTS_PAGE, CONSTANTS_PAGE * SoftMMU.PAGE_SIZE, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
            mmu.writeBlock(0x1000, constBytes, 0, constBytes.length);
            // Change to read-only after writing
            pageTable.mapPage(CONSTANTS_PAGE, CONSTANTS_PAGE * SoftMMU.PAGE_SIZE, PageEntry.PERM_READ);
        }

        // Map data pages (pages 2-3: 0x2000-0x3FFF) - R/W
        pageTable.mapPage(DATA_PAGE_START, DATA_PAGE_START * SoftMMU.PAGE_SIZE, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        pageTable.mapPage(DATA_PAGE_START + 1, (DATA_PAGE_START + 1) * SoftMMU.PAGE_SIZE, PageEntry.PERM_READ | PageEntry.PERM_WRITE);

        // Map stack pages (pages 4-5: 0x4000-0x5FFF) - R/W
        pageTable.mapPage(STACK_PAGE_START, STACK_PAGE_START * SoftMMU.PAGE_SIZE, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        pageTable.mapPage(STACK_PAGE_START + 1, (STACK_PAGE_START + 1) * SoftMMU.PAGE_SIZE, PageEntry.PERM_READ | PageEntry.PERM_WRITE);

        // Map I/O buffer pages (pages 6-7: 0x6000-0x7FFF) - R/W
        pageTable.mapPage(INPUT_BUFFER_PAGE, INPUT_BUFFER_PAGE * SoftMMU.PAGE_SIZE, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        pageTable.mapPage(OUTPUT_BUFFER_PAGE, OUTPUT_BUFFER_PAGE * SoftMMU.PAGE_SIZE, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
    }

    private void prepareInput(ExecutionContext ctx, Message message, Actor emitter,
                              Actor recipient, String channelPath) {
        // Write message properties to input buffer (page 6: 0x6000)
        int inputVAddr = ctx.inputBufferVAddr(); // 0x6000
        int offset = 0;

        // Write message text
        byte[] textBytes = message.text().getBytes(StandardCharsets.UTF_8);
        int textLen = Math.min(textBytes.length, ctx.ioBufferSize() - offset - 256); // Leave space for other fields
        ctx.softMMU().writeBlock(inputVAddr + offset, textBytes, 0, textLen);
        offset += textLen;
        // Null terminator
        ctx.softMMU().writeByte(inputVAddr + offset, 0);
        offset++;

        // Write channel
        byte[] channelBytes = channelPath.getBytes(StandardCharsets.UTF_8);
        int channelLen = Math.min(channelBytes.length, ctx.ioBufferSize() - offset - 1);
        ctx.softMMU().writeBlock(inputVAddr + offset, channelBytes, 0, channelLen);
        offset += channelLen;
        ctx.softMMU().writeByte(inputVAddr + offset, 0);
        offset++;

        // Write direction
        String dirStr = message.direction().toString();
        byte[] dirBytes = dirStr.getBytes(StandardCharsets.UTF_8);
        int dirLen = Math.min(dirBytes.length, ctx.ioBufferSize() - offset - 1);
        ctx.softMMU().writeBlock(inputVAddr + offset, dirBytes, 0, dirLen);
        offset += dirLen;
        ctx.softMMU().writeByte(inputVAddr + offset, 0);
        offset++;

        // Write emitter name
        byte[] emitterBytes = emitter.name().getBytes(StandardCharsets.UTF_8);
        int emitterLen = Math.min(emitterBytes.length, ctx.ioBufferSize() - offset - 1);
        ctx.softMMU().writeBlock(inputVAddr + offset, emitterBytes, 0, emitterLen);
        offset += emitterLen;
        ctx.softMMU().writeByte(inputVAddr + offset, 0);
        offset++;

        // Write recipient name
        byte[] recipBytes = recipient.name().getBytes(StandardCharsets.UTF_8);
        int recipLen = Math.min(recipBytes.length, ctx.ioBufferSize() - offset - 1);
        ctx.softMMU().writeBlock(inputVAddr + offset, recipBytes, 0, recipLen);
        // offset += recipLen; // Last field, no need for trailing null
    }

    private boolean allowed(Actor actor, String permission) {
        return permission == null || permissions.has(actor, permission);
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
        // Create new SoftMMU with program mappings
        SoftMMU newMMU = new SoftMMU();
        setupProgramMappings(program, newMMU);
        // Atomically publish program + memory together
        loadedProgramRef.set(new LoadedProgram(program, newMMU));
    }

    public ProgramImage program() {
        LoadedProgram loaded = loadedProgramRef.get();
        return loaded != null ? loaded.program() : null;
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
package me.majhrs16.suite.iflow.vm.service;

import me.majhrs16.suite.iflow.vm.execution.ExecutionContext;
import me.majhrs16.suite.iflow.vm.execution.ModificationJournal;
import me.majhrs16.suite.iflow.vm.isa.RegisterFile;
import me.majhrs16.suite.iflow.vm.isa.VmException;
import me.majhrs16.suite.iflow.vm.memory.SoftMMU;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Registry of host services invokable via the HOST instruction.
 * <p>
 * Services are identified by 16-bit IDs. The registry validates arguments,
 * checks capabilities, and executes the service implementation.
 * </p>
 *
 * <h2>HOST ABI v2 (register convention)</h2>
 * <pre>
 * Input:
 *   R0 = op       service ID
 *   R1 = options  service-specific parameter (scope/propId, decision, key…)
 *   R2 = base     vaddr of the argument buffer (full address)
 *   R3 = length   bytes (in: requested · out: transferred)
 *   R4            reserved for the scalar result (extras, if any, start at R5)
 *
 * Output:
 *   R0 = status   ServiceResult.Status code
 *   R1 = options  unchanged
 *   R2 = base     unchanged
 *   R3 = length   bytes transferred (reads: bytes written by the emulator;
 *                 writes: bytes consumed)
 *   R4 = result   service-specific scalar (0/1, decision…)
 * </pre>
 * <p>
 * There is no {@code offset} operand: the base address is the full address, and
 * callers add/subtract to it directly. {@code options} for property services packs
 * {@code (scope << 8) | propId} with {@code propId} in the low 8 bits.
 * </p>
 */
public final class HostServiceRegistry {

    // ===== Service IDs (matching the ABI namespace) =====

    // Property services
    public static final int PROPERTY_READ     = 0x0100;
    public static final int PROPERTY_WRITE    = 0x0101;
    public static final int PROPERTY_EXISTS   = 0x0102;

    // Permission services
    public static final int PERMISSION_CHECK  = 0x0200;
    public static final int PERMISSION_QUERY  = 0x0201;

    // Routing services
    public static final int ROUTE_DECISION    = 0x0300;
    public static final int ROUTE_REDIRECT    = 0x0301;
    public static final int ROUTE_RATE_LIMIT  = 0x0302;

    // Transformation services
    public static final int TRANSFORM_APPLY   = 0x0400;

    // State services
    public static final int STATE_READ        = 0x0500;
    public static final int STATE_WRITE       = 0x0501;

    // Channel services
    public static final int CHANNEL_RESOLVE   = 0x0600;
    public static final int CHANNEL_POLICY    = 0x0601;

    // Message services
    public static final int MESSAGE_CLONE     = 0x0700;
    public static final int MESSAGE_ID        = 0x0701;

    // ===== Property scopes (options bits 8-15) =====

    public static final int SCOPE_MESSAGE     = 0x01;
    public static final int SCOPE_EMITTER     = 0x02;
    public static final int SCOPE_RECIPIENT   = 0x03;
    public static final int SCOPE_CHANNEL     = 0x04;
    public static final int SCOPE_SERVER      = 0x05;

    // ===== Property IDs (options bits 0-7) =====
    //
    // propId is 8-bit and namespaced by scope: the same numeric id means different
    // things in the MESSAGE, ACTOR and CHANNEL scopes. Actor/channel ids were
    // previously 0x0100/0x0200 (16-bit), which does not fit the low-8-bit field.

    // Message scope
    public static final int PROP_CONTENT      = 0x10;
    public static final int PROP_CHANNEL      = 0x11;
    public static final int PROP_DIRECTION    = 0x12;
    public static final int PROP_TYPE         = 0x13;
    public static final int PROP_LANG_SOURCE  = 0x14;
    public static final int PROP_LANG_TARGET  = 0x15;
    public static final int PROP_COLOR_MODE   = 0x16;
    public static final int PROP_FORMAT_PAPI  = 0x17;
    public static final int PROP_SLEEP_MILLIS = 0x18;
    public static final int PROP_SOUNDS       = 0x19;
    public static final int PROP_CANCELLED    = 0x1A;
    public static final int PROP_PROCESSED    = 0x1B;
    public static final int PROP_TRANSLATE    = 0x1C;

    // Actor scope
    public static final int PROP_ACTOR_NAME   = 0x00;
    public static final int PROP_ACTOR_UUID   = 0x01;
    public static final int PROP_ACTOR_LANG   = 0x02;
    public static final int PROP_ACTOR_WORLD  = 0x03;

    // Channel scope
    public static final int PROP_CHAN_NAME    = 0x00;
    public static final int PROP_CHAN_RATE    = 0x01;
    public static final int PROP_CHAN_SEND_POL = 0x02;
    public static final int PROP_CHAN_RECV_POL = 0x03;

    // ===== Routing decision codes (options) =====

    public static final int DECISION_LOG         = 0;
    public static final int DECISION_DROP        = 1;
    public static final int DECISION_REJECT      = 2;
    public static final int DECISION_REDIRECT    = 3;
    public static final int DECISION_CHAN_REDIR  = 4;
    public static final int DECISION_RATE_LIMIT  = 5;
    public static final int DECISION_CONTINUE    = 6;

    private final Map<Integer, HostService> services = new HashMap<>();
    private final Map<String, Integer> capabilityMap = new HashMap<>();

    // Host-injected dependencies
    private BiFunction<String, String, Boolean> permissionChecker;
    private java.util.function.Function<String, String> placeholderResolver;
    private java.util.function.Function<String, String> translationDetector;
    private java.util.function.BiFunction<Integer, String, String> propertyReader;
    private java.util.function.BiConsumer<Integer, String> propertyWriter;
    private java.util.function.Function<Integer, String> channelResolver;
    private java.util.function.Supplier<java.util.Map<Integer, Object>> stateReader;
    private java.util.function.BiConsumer<Integer, Object> stateWriter;
    private me.majhrs16.suite.iflow.channel.RateLimiter rateLimiter;

    public HostServiceRegistry() {
        registerBuiltinServices();
    }

    private void registerBuiltinServices() {
        // Property read
        register(PROPERTY_READ, new HostService() {
            @Override public ServiceResult invoke(ExecutionContext ctx, int argVAddr, int argLength, int options) {
                return doPropertyRead(ctx, argVAddr, argLength, options);
            }
            @Override public int baseCost() { return 10; }
            @Override public int varCostPerByte() { return 1; }
            @Override public String[] requiredCapabilities() { return new String[]{"property.read"}; }
        });

        // Property write
        register(PROPERTY_WRITE, new HostService() {
            @Override public ServiceResult invoke(ExecutionContext ctx, int argVAddr, int argLength, int options) {
                return doPropertyWrite(ctx, argVAddr, argLength, options);
            }
            @Override public int baseCost() { return 15; }
            @Override public int varCostPerByte() { return 2; }
            @Override public String[] requiredCapabilities() { return new String[]{"property.write"}; }
        });

        // Permission check
        register(PERMISSION_CHECK, new HostService() {
            @Override public ServiceResult invoke(ExecutionContext ctx, int argVAddr, int argLength, int options) {
                return doPermissionCheck(ctx, argVAddr, argLength, options);
            }
            @Override public int baseCost() { return 5; }
            @Override public int varCostPerByte() { return 0; }
            @Override public String[] requiredCapabilities() { return new String[]{"permission.check"}; }
        });

        // Route decision
        register(ROUTE_DECISION, new HostService() {
            @Override public ServiceResult invoke(ExecutionContext ctx, int argVAddr, int argLength, int options) {
                return doRouteDecision(ctx, argVAddr, argLength, options);
            }
            @Override public int baseCost() { return 20; }
            @Override public int varCostPerByte() { return 0; }
            @Override public String[] requiredCapabilities() { return new String[]{"routing.decide"}; }
        });

        // State read/write
        register(STATE_READ, new HostService() {
            @Override public ServiceResult invoke(ExecutionContext ctx, int argVAddr, int argLength, int options) {
                return doStateRead(ctx, argVAddr, argLength, options);
            }
            @Override public int baseCost() { return 10; }
            @Override public int varCostPerByte() { return 1; }
            @Override public String[] requiredCapabilities() { return new String[]{"state.read"}; }
        });

        register(STATE_WRITE, new HostService() {
            @Override public ServiceResult invoke(ExecutionContext ctx, int argVAddr, int argLength, int options) {
                return doStateWrite(ctx, argVAddr, argLength, options);
            }
            @Override public int baseCost() { return 15; }
            @Override public int varCostPerByte() { return 2; }
            @Override public String[] requiredCapabilities() { return new String[]{"state.write"}; }
        });

        // Channel resolve
        register(CHANNEL_RESOLVE, new HostService() {
            @Override public ServiceResult invoke(ExecutionContext ctx, int argVAddr, int argLength, int options) {
                return doChannelResolve(ctx, argVAddr, argLength, options);
            }
            @Override public int baseCost() { return 10; }
            @Override public int varCostPerByte() { return 1; }
            @Override public String[] requiredCapabilities() { return new String[]{"channel.resolve"}; }
        });
    }

    public void register(int serviceId, HostService service) {
        services.put(serviceId, service);
        // Automatically declare required capabilities
        for (String cap : service.requiredCapabilities()) {
            declareCapability(cap);
        }
    }

    public HostService getService(int serviceId) {
        return services.get(serviceId);
    }

    public boolean hasService(int serviceId) {
        return services.containsKey(serviceId);
    }

    /** Declares a capability that this program may use. */
    public void declareCapability(String capability) {
        capabilityMap.put(capability, 1);
    }

    /**
     * Capabilities the host grants (its allowlist): the union of everything
     * declared via {@link #declareCapability} and every registered service's
     * {@link HostService#requiredCapabilities()}. A program can never widen this.
     */
    public java.util.Set<String> declaredCapabilities() {
        return java.util.Set.copyOf(capabilityMap.keySet());
    }

    /**
     * Invokes a service, checking capabilities and charging fuel.
     *
     * @param argVAddr base address of the argument buffer (R2)
     * @param argLength requested byte count (R3)
     * @param options   service-specific parameter (R1)
     */
    public ServiceResult invoke(ExecutionContext ctx, int serviceId, int argVAddr, int argLength, int options) {
        HostService service = services.get(serviceId);
        if (service == null) {
            // Unknown/optional service: report UNSUPPORTED in R0 (ABI status 5) so
            // the program can negotiate capabilities. It must not be a hard error:
            // throwing here was turned into a REJECT by VmPolicyEngine, contradicting
            // the documented contract.
            return ServiceResult.unsupported();
        }

        // Check declared capabilities against this execution's allowed capabilities
        for (String cap : service.requiredCapabilities()) {
            if (!ctx.hasCapability(cap)) {
                throw VmException.hostServiceDenied(serviceId, ctx.registers().get(RegisterFile.IP));
            }
        }

        // Charge base fuel cost
        ctx.consumeFuel(service.baseCost());

        // Invoke service
        ServiceResult result = service.invoke(ctx, argVAddr, argLength, options);

        // Charge variable fuel cost based on bytes transferred
        if (result.bytesTransferred() > 0) {
            ctx.consumeFuel(service.varCostPerByte() * result.bytesTransferred());
        }

        return result;
    }

    // ===== Service implementations =====

    private ServiceResult doPropertyRead(ExecutionContext ctx, int argVAddr, int argLength, int options) {
        int scope = (options >> 8) & 0xFF;
        int propId = options & 0x00FF;

        if (propertyReader == null) {
            return ServiceResult.hostFailure("Property reader not configured");
        }

        String value = propertyReader.apply(scope, String.valueOf(propId));
        if (value == null) {
            return ServiceResult.notFound();
        }

        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > argLength) {
            return ServiceResult.bufferTooSmall(bytes.length);
        }

        try {
            ctx.softMMU().writeBlock(argVAddr, bytes, 0, bytes.length);
        } catch (VmException e) {
            return ServiceResult.hostFailure("Memory write failed: " + e.getMessage());
        }

        return ServiceResult.ok(bytes.length);
    }

    private ServiceResult doPropertyWrite(ExecutionContext ctx, int argVAddr, int argLength, int options) {
        int scope = (options >> 8) & 0xFF;
        int propId = options & 0x00FF;

        if (propertyWriter == null) {
            return ServiceResult.hostFailure("Property writer not configured");
        }

        byte[] bytes;
        try {
            bytes = ctx.softMMU().getRegion(argVAddr, argLength);
        } catch (VmException e) {
            return ServiceResult.hostFailure("Memory read failed: " + e.getMessage());
        }

        String value = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);

        // Record in journal for deferred commit
        ctx.journal().record(new ModificationJournal.Entry(
            ModificationJournal.OpType.SET_TEXT, value, "propWrite:" + propId
        ));

        return ServiceResult.ok(argLength);
    }

    private ServiceResult doPermissionCheck(ExecutionContext ctx, int argVAddr, int argLength, int options) {
        if (permissionChecker == null) {
            return ServiceResult.hostFailure("Permission checker not configured");
        }

        byte[] bytes;
        try {
            bytes = ctx.softMMU().getRegion(argVAddr, argLength);
        } catch (VmException e) {
            return ServiceResult.hostFailure("Memory read failed: " + e.getMessage());
        }

        // Parse as null-terminated strings: actorName\0permNode
        String data = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        String[] parts = data.split("\0", 2);
        if (parts.length < 2) {
            return ServiceResult.invalidArgument("Invalid permission check args");
        }

        boolean allowed = permissionChecker.apply(parts[0], parts[1]);
        return ServiceResult.ok(argLength, allowed ? 1 : 0);
    }

    private ServiceResult doRouteDecision(ExecutionContext ctx, int argVAddr, int argLength, int options) {
        int decision = options & 0x00FF; // Low 8 bits = decision code
        String redirectChannel = null;
        String redirectTarget = null;
        int bytesRead = 0;

        if (decision == DECISION_CHAN_REDIR && argLength > 0) {
            byte[] bytes;
            try {
                bytes = ctx.softMMU().getRegion(argVAddr, argLength);
            } catch (VmException e) {
                return ServiceResult.hostFailure("Memory read failed: " + e.getMessage());
            }
            redirectChannel = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            bytesRead = argLength;
        } else if (decision == DECISION_REDIRECT && argLength > 0) {
            // REDIRECT: read target actor from argument buffer
            byte[] bytes;
            try {
                bytes = ctx.softMMU().getRegion(argVAddr, argLength);
            } catch (VmException e) {
                return ServiceResult.hostFailure("Memory read failed: " + e.getMessage());
            }
            redirectTarget = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            bytesRead = argLength;
        }

        // Calculate rate-limit backoff before the switch so memory faults can early-return.
        long rateLimitBackoff = 0;
        if (decision == DECISION_RATE_LIMIT && rateLimiter != null && argLength > 0) {
            byte[] bytes;
            try {
                bytes = ctx.softMMU().getRegion(argVAddr, argLength);
            } catch (VmException e) {
                return ServiceResult.hostFailure("Memory read failed: " + e.getMessage());
            }
            String rateLimitKey = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            rateLimitBackoff = rateLimiter.nanosUntilNextWindow(rateLimitKey) / 1_000_000; // Convert to millis
            if (rateLimitBackoff < 0) rateLimitBackoff = 0;
            bytesRead = argLength;
        }

        // Record in journal
        ModificationJournal.Entry entry = switch (decision) {
            case DECISION_LOG -> new ModificationJournal.Entry(ModificationJournal.OpType.SET_PROCESSED, true, "route:LOG");
            case DECISION_DROP -> new ModificationJournal.Entry(ModificationJournal.OpType.CANCEL_MESSAGE, true, "route:DROP");
            case DECISION_REJECT -> new ModificationJournal.Entry(ModificationJournal.OpType.CANCEL_MESSAGE, true, "route:REJECT");
            case DECISION_REDIRECT -> redirectTarget != null
                ? new ModificationJournal.Entry(ModificationJournal.OpType.REDIRECT_TARGET, redirectTarget, "route:REDIRECT")
                : new ModificationJournal.Entry(ModificationJournal.OpType.SET_PROCESSED, true, "route:REDIRECT");
            case DECISION_CHAN_REDIR -> new ModificationJournal.Entry(ModificationJournal.OpType.REDIRECT_CHANNEL, redirectChannel, "route:CHANNEL_REDIRECT");
            case DECISION_RATE_LIMIT -> new ModificationJournal.Entry(ModificationJournal.OpType.RATE_LIMIT_BACKOFF, rateLimitBackoff, "route:RATE_LIMIT");
            case DECISION_CONTINUE -> new ModificationJournal.Entry(ModificationJournal.OpType.SET_PROCESSED, true, "route:CONTINUE");
            default -> new ModificationJournal.Entry(ModificationJournal.OpType.SET_PROCESSED, true, "route:UNKNOWN");
        };
        ctx.journal().record(entry);

        return ServiceResult.ok(bytesRead, decision);
    }

    private ServiceResult doStateRead(ExecutionContext ctx, int argVAddr, int argLength, int options) {
        if (stateReader == null) {
            return ServiceResult.hostFailure("State reader not configured");
        }
        Integer key = options & 0xFFFF;
        Object value = stateReader.get().get(key);
        if (value == null) {
            return ServiceResult.notFound();
        }
        String str = value.toString();
        byte[] bytes = str.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > argLength) {
            return ServiceResult.bufferTooSmall(bytes.length);
        }
        try {
            ctx.softMMU().writeBlock(argVAddr, bytes, 0, bytes.length);
        } catch (VmException e) {
            return ServiceResult.hostFailure("Memory write failed: " + e.getMessage());
        }
        return ServiceResult.ok(bytes.length);
    }

    private ServiceResult doStateWrite(ExecutionContext ctx, int argVAddr, int argLength, int options) {
        if (stateWriter == null) {
            return ServiceResult.hostFailure("State writer not configured");
        }
        Integer key = options & 0xFFFF;
        byte[] bytes;
        try {
            bytes = ctx.softMMU().getRegion(argVAddr, argLength);
        } catch (VmException e) {
            return ServiceResult.hostFailure("Memory read failed: " + e.getMessage());
        }
        String value = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        stateWriter.accept(key, value);
        return ServiceResult.ok(argLength);
    }

    private ServiceResult doChannelResolve(ExecutionContext ctx, int argVAddr, int argLength, int options) {
        if (channelResolver == null) {
            return ServiceResult.hostFailure("Channel resolver not configured");
        }
        byte[] bytes;
        try {
            bytes = ctx.softMMU().getRegion(argVAddr, argLength);
        } catch (VmException e) {
            return ServiceResult.hostFailure("Memory read failed: " + e.getMessage());
        }
        String channelName = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        String channelId = channelResolver.apply(channelName.hashCode());
        if (channelId == null) {
            return ServiceResult.notFound();
        }
        byte[] resultBytes = channelId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (resultBytes.length > argLength) {
            return ServiceResult.bufferTooSmall(resultBytes.length);
        }
        try {
            ctx.softMMU().writeBlock(argVAddr, resultBytes, 0, resultBytes.length);
        } catch (VmException e) {
            return ServiceResult.hostFailure("Memory write failed: " + e.getMessage());
        }
        return ServiceResult.ok(resultBytes.length);
    }

    // ===== Host dependency injection =====

    public HostServiceRegistry permissionChecker(BiFunction<String, String, Boolean> checker) {
        this.permissionChecker = checker;
        return this;
    }

    public HostServiceRegistry placeholderResolver(java.util.function.Function<String, String> resolver) {
        this.placeholderResolver = resolver;
        return this;
    }

    public HostServiceRegistry translationDetector(java.util.function.Function<String, String> detector) {
        this.translationDetector = detector;
        return this;
    }

    public HostServiceRegistry propertyReader(java.util.function.BiFunction<Integer, String, String> reader) {
        this.propertyReader = reader;
        return this;
    }

    public HostServiceRegistry propertyWriter(java.util.function.BiConsumer<Integer, String> writer) {
        this.propertyWriter = writer;
        return this;
    }

    public HostServiceRegistry channelResolver(java.util.function.Function<Integer, String> resolver) {
        this.channelResolver = resolver;
        return this;
    }

    public HostServiceRegistry stateReader(java.util.function.Supplier<java.util.Map<Integer, Object>> reader) {
        this.stateReader = reader;
        return this;
    }

    public HostServiceRegistry stateWriter(java.util.function.BiConsumer<Integer, Object> writer) {
        this.stateWriter = writer;
        return this;
    }

    public HostServiceRegistry rateLimiter(me.majhrs16.suite.iflow.channel.RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
        return this;
    }

    public interface HostService {
        ServiceResult invoke(ExecutionContext ctx, int argVAddr, int argLength, int options);
        int baseCost();
        int varCostPerByte();
        String[] requiredCapabilities();
    }
}

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
 * <p>
 * ABI Convention for HOST instruction:
 *   R0 = service ID
 *   R1 = virtual address (vaddr) of argument buffer
 *   R2 = offset within buffer
 *   R3 = length / options
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

    // ===== Property scopes =====

    public static final int SCOPE_MESSAGE     = 0x01;
    public static final int SCOPE_EMITTER     = 0x02;
    public static final int SCOPE_RECIPIENT   = 0x03;
    public static final int SCOPE_CHANNEL     = 0x04;
    public static final int SCOPE_SERVER      = 0x05;

    // ===== Property IDs (message scope) =====

    public static final int PROP_CONTENT      = 0x0010;
    public static final int PROP_CHANNEL      = 0x0011;
    public static final int PROP_DIRECTION    = 0x0012;
    public static final int PROP_TYPE         = 0x0013;
    public static final int PROP_LANG_SOURCE  = 0x0014;
    public static final int PROP_LANG_TARGET  = 0x0015;
    public static final int PROP_COLOR_MODE   = 0x0016;
    public static final int PROP_FORMAT_PAPI  = 0x0017;
    public static final int PROP_SLEEP_MILLIS = 0x0018;
    public static final int PROP_SOUNDS       = 0x0019;
    public static final int PROP_CANCELLED    = 0x001A;
    public static final int PROP_PROCESSED    = 0x001B;
    public static final int PROP_TRANSLATE    = 0x001C;

    // ===== Property IDs (actor scope) =====

    public static final int PROP_ACTOR_NAME   = 0x0100;
    public static final int PROP_ACTOR_UUID   = 0x0101;
    public static final int PROP_ACTOR_LANG   = 0x0102;
    public static final int PROP_ACTOR_WORLD  = 0x0103;

    // ===== Property IDs (channel scope) =====

    public static final int PROP_CHAN_NAME    = 0x0200;
    public static final int PROP_CHAN_RATE    = 0x0201;
    public static final int PROP_CHAN_SEND_POL = 0x0202;
    public static final int PROP_CHAN_RECV_POL = 0x0203;

    // ===== Routing decision codes =====

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

    public HostServiceRegistry() {
        registerBuiltinServices();
    }

    private void registerBuiltinServices() {
        // Property read
        register(PROPERTY_READ, new HostService() {
            @Override public ServiceResult invoke(ExecutionContext ctx, int argVAddr, int argOffset, int argLength, int options) {
                return doPropertyRead(ctx, argVAddr, argOffset, argLength, options);
            }
            @Override public int baseCost() { return 10; }
            @Override public int varCostPerByte() { return 1; }
            @Override public String[] requiredCapabilities() { return new String[]{"property.read"}; }
        });

        // Property write
        register(PROPERTY_WRITE, new HostService() {
            @Override public ServiceResult invoke(ExecutionContext ctx, int argVAddr, int argOffset, int argLength, int options) {
                return doPropertyWrite(ctx, argVAddr, argOffset, argLength, options);
            }
            @Override public int baseCost() { return 15; }
            @Override public int varCostPerByte() { return 2; }
            @Override public String[] requiredCapabilities() { return new String[]{"property.write"}; }
        });

        // Permission check
        register(PERMISSION_CHECK, new HostService() {
            @Override public ServiceResult invoke(ExecutionContext ctx, int argVAddr, int argOffset, int argLength, int options) {
                return doPermissionCheck(ctx, argVAddr, argOffset, argLength, options);
            }
            @Override public int baseCost() { return 5; }
            @Override public int varCostPerByte() { return 0; }
            @Override public String[] requiredCapabilities() { return new String[]{"permission.check"}; }
        });

        // Route decision
        register(ROUTE_DECISION, new HostService() {
            @Override public ServiceResult invoke(ExecutionContext ctx, int argVAddr, int argOffset, int argLength, int options) {
                return doRouteDecision(ctx, argVAddr, argOffset, argLength, options);
            }
            @Override public int baseCost() { return 20; }
            @Override public int varCostPerByte() { return 0; }
            @Override public String[] requiredCapabilities() { return new String[]{"routing.decide"}; }
        });

        // State read/write
        register(STATE_READ, new HostService() {
            @Override public ServiceResult invoke(ExecutionContext ctx, int argVAddr, int argOffset, int argLength, int options) {
                return doStateRead(ctx, argVAddr, argOffset, argLength, options);
            }
            @Override public int baseCost() { return 10; }
            @Override public int varCostPerByte() { return 1; }
            @Override public String[] requiredCapabilities() { return new String[]{"state.read"}; }
        });

        register(STATE_WRITE, new HostService() {
            @Override public ServiceResult invoke(ExecutionContext ctx, int argVAddr, int argOffset, int argLength, int options) {
                return doStateWrite(ctx, argVAddr, argOffset, argLength, options);
            }
            @Override public int baseCost() { return 15; }
            @Override public int varCostPerByte() { return 2; }
            @Override public String[] requiredCapabilities() { return new String[]{"state.write"}; }
        });

        // Channel resolve
        register(CHANNEL_RESOLVE, new HostService() {
            @Override public ServiceResult invoke(ExecutionContext ctx, int argVAddr, int argOffset, int argLength, int options) {
                return doChannelResolve(ctx, argVAddr, argOffset, argLength, options);
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

    /** Checks if a capability is declared (not necessarily granted). */
    public boolean hasCapability(String capability) {
        return capabilityMap.containsKey(capability);
    }

    /** Invokes a service, checking capabilities and charging fuel. */
    public ServiceResult invoke(ExecutionContext ctx, int serviceId, int argVAddr, int argOffset, int argLength, int options) {
        HostService service = services.get(serviceId);
        if (service == null) {
            throw VmException.hostServiceNotFound(serviceId, ctx.registers().get(RegisterFile.IP));
        }

        // Check declared capabilities
        for (String cap : service.requiredCapabilities()) {
            if (!hasCapability(cap)) {
                throw VmException.hostServiceDenied(serviceId, ctx.registers().get(RegisterFile.IP));
            }
        }

        // Charge base fuel cost
        ctx.consumeFuel(service.baseCost());

        // Invoke service
        ServiceResult result = service.invoke(ctx, argVAddr, argOffset, argLength, options);

        // Charge variable fuel cost based on bytes transferred
        if (result.bytesTransferred() > 0) {
            ctx.consumeFuel(service.varCostPerByte() * result.bytesTransferred());
        }

        return result;
    }

    // ===== Service implementations =====

    private ServiceResult doPropertyRead(ExecutionContext ctx, int argVAddr, int argOffset, int argLength, int options) {
        int scope = (options >> 8) & 0xFF;
        int propId = options & 0xFFFF;

        if (propertyReader == null) {
            return ServiceResult.hostFailure("Property reader not configured");
        }

        String value = propertyReader.apply(scope, String.valueOf(propId));
        if (value == null) {
            return ServiceResult.notFound();
        }

        // Write to output buffer
        int targetVAddr = argVAddr + argOffset;
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (argOffset + bytes.length > argLength) {
            return ServiceResult.bufferTooSmall(bytes.length);
        }

        try {
            ctx.softMMU().writeBlock(targetVAddr, bytes, 0, bytes.length);
        } catch (VmException e) {
            return ServiceResult.hostFailure("Memory write failed: " + e.getMessage());
        }

        return ServiceResult.ok(bytes.length, bytes.length, 0);
    }

    private ServiceResult doPropertyWrite(ExecutionContext ctx, int argVAddr, int argOffset, int argLength, int options) {
        int scope = (options >> 8) & 0xFF;
        int propId = options & 0xFFFF;

        if (propertyWriter == null) {
            return ServiceResult.hostFailure("Property writer not configured");
        }

        // Read from input buffer
        int sourceVAddr = argVAddr + argOffset;
        byte[] bytes;
        try {
            bytes = ctx.softMMU().getRegion(sourceVAddr, argLength);
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

    private ServiceResult doPermissionCheck(ExecutionContext ctx, int argVAddr, int argOffset, int argLength, int options) {
        if (permissionChecker == null) {
            return ServiceResult.hostFailure("Permission checker not configured");
        }

        // Read actor ID and permission from input buffer at argVAddr + argOffset
        int sourceVAddr = argVAddr + argOffset;
        byte[] bytes;
        try {
            bytes = ctx.softMMU().getRegion(sourceVAddr, argLength);
        } catch (VmException e) {
            return ServiceResult.hostFailure("Memory read failed: " + e.getMessage());
        }

        // Parse as null-terminated strings: actorName\0permNode\0
        String data = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        String[] parts = data.split("\0", 2);
        if (parts.length < 2) {
            return ServiceResult.invalidArgument("Invalid permission check args");
        }

        String actorName = parts[0];
        String perm = parts[1];

        boolean allowed = permissionChecker.apply(actorName, perm);
        return ServiceResult.ok(allowed ? 1 : 0);
    }

    private ServiceResult doRouteDecision(ExecutionContext ctx, int argVAddr, int argOffset, int argLength, int options) {
        int decision = options & 0xFFFF; // Low 16 bits = decision code
        String redirectChannel = null;

        if (decision == DECISION_CHAN_REDIR && argLength > 0) {
            int sourceVAddr = argVAddr + argOffset;
            byte[] bytes;
            try {
                bytes = ctx.softMMU().getRegion(sourceVAddr, argLength);
            } catch (VmException e) {
                return ServiceResult.hostFailure("Memory read failed: " + e.getMessage());
            }

            redirectChannel = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }

        // Record in journal
        ModificationJournal.Entry entry = switch (decision) {
            case DECISION_LOG -> new ModificationJournal.Entry(ModificationJournal.OpType.SET_PROCESSED, true, "route:LOG");
            case DECISION_DROP -> new ModificationJournal.Entry(ModificationJournal.OpType.CANCEL_MESSAGE, true, "route:DROP");
            case DECISION_REJECT -> new ModificationJournal.Entry(ModificationJournal.OpType.CANCEL_MESSAGE, true, "route:REJECT");
            case DECISION_REDIRECT -> new ModificationJournal.Entry(ModificationJournal.OpType.SET_PROCESSED, true, "route:REDIRECT");
            case DECISION_CHAN_REDIR -> new ModificationJournal.Entry(ModificationJournal.OpType.REDIRECT_CHANNEL, redirectChannel, "route:CHANNEL_REDIRECT");
            case DECISION_RATE_LIMIT -> new ModificationJournal.Entry(ModificationJournal.OpType.SET_PROCESSED, true, "route:RATE_LIMIT");
            case DECISION_CONTINUE -> new ModificationJournal.Entry(ModificationJournal.OpType.SET_PROCESSED, true, "route:CONTINUE");
            default -> new ModificationJournal.Entry(ModificationJournal.OpType.SET_PROCESSED, true, "route:UNKNOWN");
        };
        ctx.journal().record(entry);

        return ServiceResult.ok(decision);
    }

    private ServiceResult doStateRead(ExecutionContext ctx, int argVAddr, int argOffset, int argLength, int options) {
        if (stateReader == null) {
            return ServiceResult.hostFailure("State reader not configured");
        }
        Integer key = options & 0xFFFF;
        Object value = stateReader.get().get(key);
        if (value == null) {
            return ServiceResult.notFound();
        }
        String str = value.toString();
        int targetVAddr = argVAddr + argOffset;
        byte[] bytes = str.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (argOffset + bytes.length > argLength) {
            return ServiceResult.bufferTooSmall(bytes.length);
        }
        try {
            ctx.softMMU().writeBlock(targetVAddr, bytes, 0, bytes.length);
        } catch (VmException e) {
            return ServiceResult.hostFailure("Memory write failed: " + e.getMessage());
        }
        return ServiceResult.ok(bytes.length, bytes.length, 0);
    }

    private ServiceResult doStateWrite(ExecutionContext ctx, int argVAddr, int argOffset, int argLength, int options) {
        if (stateWriter == null) {
            return ServiceResult.hostFailure("State writer not configured");
        }
        Integer key = options & 0xFFFF;
        int sourceVAddr = argVAddr + argOffset;
        byte[] bytes;
        try {
            bytes = ctx.softMMU().getRegion(sourceVAddr, argLength);
        } catch (VmException e) {
            return ServiceResult.hostFailure("Memory read failed: " + e.getMessage());
        }
        String value = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        stateWriter.accept(key, value);
        return ServiceResult.ok(argLength);
    }

    private ServiceResult doChannelResolve(ExecutionContext ctx, int argVAddr, int argOffset, int argLength, int options) {
        if (channelResolver == null) {
            return ServiceResult.hostFailure("Channel resolver not configured");
        }
        int sourceVAddr = argVAddr + argOffset;
        byte[] bytes;
        try {
            bytes = ctx.softMMU().getRegion(sourceVAddr, argLength);
        } catch (VmException e) {
            return ServiceResult.hostFailure("Memory read failed: " + e.getMessage());
        }
        String channelName = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        String channelId = channelResolver.apply(channelName.hashCode());
        if (channelId == null) {
            return ServiceResult.notFound();
        }
        int targetVAddr = argVAddr + argOffset;
        byte[] resultBytes = channelId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (argOffset + resultBytes.length > argLength) {
            return ServiceResult.bufferTooSmall(resultBytes.length);
        }
        try {
            ctx.softMMU().writeBlock(targetVAddr, resultBytes, 0, resultBytes.length);
        } catch (VmException e) {
            return ServiceResult.hostFailure("Memory write failed: " + e.getMessage());
        }
        return ServiceResult.ok(resultBytes.length, resultBytes.length, 0);
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

    public interface HostService {
        ServiceResult invoke(ExecutionContext ctx, int argVAddr, int argOffset, int argLength, int options);
        int baseCost();
        int varCostPerByte();
        String[] requiredCapabilities();
    }
}

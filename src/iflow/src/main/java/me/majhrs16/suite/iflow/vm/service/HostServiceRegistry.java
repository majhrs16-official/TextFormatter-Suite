package me.majhrs16.suite.iflow.vm.service;

import me.majhrs16.suite.iflow.vm.execution.ExecutionContext;
import me.majhrs16.suite.iflow.vm.execution.ModificationJournal;
import me.majhrs16.suite.iflow.vm.isa.RegisterFile;
import me.majhrs16.suite.iflow.vm.isa.VmException;
import me.majhrs16.suite.logger.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * <p>
 * Argument and result buffers are moved between host and VM memory through the
 * execution's {@link me.majhrs16.suite.iflow.vm.memory.VmDmaController} (D17),
 * never by touching {@code SoftMMU} directly: {@code R2}/{@code R3} describe the
 * transfer (base/length), so the DMA is the canonical host&harr;VM transfer path.
 * </p>
 */
public final class HostServiceRegistry {

    // ===== Service IDs (matching the ABI namespace) =====
    //
    // Every declared ID is now registered and functional (D26): PROPERTY_READ,
    // PROPERTY_WRITE, PROPERTY_EXISTS, PERMISSION_CHECK, PERMISSION_QUERY,
    // ROUTE_DECISION, ROUTE_REDIRECT, ROUTE_RATE_LIMIT, TRANSFORM_APPLY,
    // STATE_READ, STATE_WRITE, CHANNEL_RESOLVE, CHANNEL_POLICY, MESSAGE_CLONE and
    // MESSAGE_ID. Any other id still returns UNSUPPORTED.

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

    // ===== Transform kinds (options for TRANSFORM_APPLY) =====

    /** Resolve external placeholders in the buffer text. */
    public static final int TRANSFORM_RESOLVE_PLACEHOLDERS = 0;
    /** Detect the language of the buffer text. */
    public static final int TRANSFORM_DETECT_LANGUAGE      = 1;

    // ===== Redirect kinds (options for ROUTE_REDIRECT) =====

    /** Redirect the message to a specific actor (default). */
    public static final int REDIRECT_KIND_ACTOR   = 0;
    /** Redirect the message to a channel. */
    public static final int REDIRECT_KIND_CHANNEL = 1;

    // ===== Channel policies (options for CHANNEL_POLICY) =====

    /** The channel's send policy. */
    public static final int CHANNEL_POLICY_SEND    = 0;
    /** The channel's receive policy. */
    public static final int CHANNEL_POLICY_RECEIVE = 1;
    /** The channel's rate limit (messages per second). */
    public static final int CHANNEL_POLICY_RATE    = 2;

    private final Map<Integer, HostService> services = new HashMap<>();
    private final Map<String, Integer> capabilityMap = new HashMap<>();

    // Host-injected dependencies
    private BiFunction<String, String, Boolean> permissionChecker;
    /**
     * Resolves external placeholders (e.g. PlaceholderAPI) in a text. The host
     * binds the actor in the lambda. Backs TRANSFORM_APPLY kind 0.
     */
    private java.util.function.Function<String, String> placeholderResolver;
    /**
     * Detects the language code of a text. Backs TRANSFORM_APPLY kind 1 and the
     * auto-detection of an unset source language in PROPERTY_READ.
     */
    private java.util.function.Function<String, String> translationDetector;
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

        // Transform apply (placeholder resolution / language detection)
        register(TRANSFORM_APPLY, new HostService() {
            @Override public ServiceResult invoke(ExecutionContext ctx, int argVAddr, int argLength, int options) {
                return doTransformApply(ctx, argVAddr, argLength, options);
            }
            @Override public int baseCost() { return 20; }
            @Override public int varCostPerByte() { return 1; }
            @Override public String[] requiredCapabilities() { return new String[]{"transform.apply"}; }
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

        // Property exists: no bytes move, the answer travels in R4 (D26).
        register(PROPERTY_EXISTS, new HostService() {
            @Override public ServiceResult invoke(ExecutionContext ctx, int argVAddr, int argLength, int options) {
                int scope = (options >> 8) & 0xFF;
                int propId = options & 0x00FF;
                Optional<String> value = ctx.hostContext().readProperty(scope, propId);
                return ServiceResult.ok(0, value.isPresent() ? 1 : 0);
            }
            @Override public int baseCost() { return 5; }
            @Override public int varCostPerByte() { return 0; }
            @Override public String[] requiredCapabilities() { return new String[]{"property.read"}; }
        });

        // Permission query: like PERMISSION_CHECK but reports the outcome in R0.
        register(PERMISSION_QUERY, new HostService() {
            @Override public ServiceResult invoke(ExecutionContext ctx, int argVAddr, int argLength, int options) {
                return doPermissionQuery(ctx, argVAddr, argLength, options);
            }
            @Override public int baseCost() { return 5; }
            @Override public int varCostPerByte() { return 0; }
            @Override public String[] requiredCapabilities() { return new String[]{"permission.check"}; }
        });

        // Route redirect: journal a target/channel redirect directly.
        register(ROUTE_REDIRECT, new HostService() {
            @Override public ServiceResult invoke(ExecutionContext ctx, int argVAddr, int argLength, int options) {
                return doRouteRedirect(ctx, argVAddr, argLength, options);
            }
            @Override public int baseCost() { return 20; }
            @Override public int varCostPerByte() { return 0; }
            @Override public String[] requiredCapabilities() { return new String[]{"routing.decide"}; }
        });

        // Route rate limit: journal a backoff computed by the rate limiter.
        register(ROUTE_RATE_LIMIT, new HostService() {
            @Override public ServiceResult invoke(ExecutionContext ctx, int argVAddr, int argLength, int options) {
                return doRouteRateLimit(ctx, argVAddr, argLength, options);
            }
            @Override public int baseCost() { return 20; }
            @Override public int varCostPerByte() { return 0; }
            @Override public String[] requiredCapabilities() { return new String[]{"routing.decide"}; }
        });

        // Channel policy: read a named channel's send/receive/rate policy.
        register(CHANNEL_POLICY, new HostService() {
            @Override public ServiceResult invoke(ExecutionContext ctx, int argVAddr, int argLength, int options) {
                return doChannelPolicy(ctx, argVAddr, argLength, options);
            }
            @Override public int baseCost() { return 10; }
            @Override public int varCostPerByte() { return 1; }
            @Override public String[] requiredCapabilities() { return new String[]{"channel.resolve"}; }
        });

        // Message clone: copy the serialized message payload into VM memory.
        register(MESSAGE_CLONE, new HostService() {
            @Override public ServiceResult invoke(ExecutionContext ctx, int argVAddr, int argLength, int options) {
                return doMessageClone(ctx, argVAddr, argLength, options);
            }
            @Override public int baseCost() { return 15; }
            @Override public int varCostPerByte() { return 1; }
            @Override public String[] requiredCapabilities() { return new String[0]; }
        });

        // Message id: materialize the current message id.
        register(MESSAGE_ID, new HostService() {
            @Override public ServiceResult invoke(ExecutionContext ctx, int argVAddr, int argLength, int options) {
                return doMessageId(ctx, argVAddr, argLength, options);
            }
            @Override public int baseCost() { return 10; }
            @Override public int varCostPerByte() { return 1; }
            @Override public String[] requiredCapabilities() { return new String[0]; }
        });
    }

    public void register(int serviceId, HostService service) {
        services.put(serviceId, service);
        // Automatically declare required capabilities
        for (String cap : service.requiredCapabilities()) {
            declareCapability(cap);
        }
    }

    public Optional<HostService> getService(int serviceId) {
        return Optional.ofNullable(services.get(serviceId));
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
            return ServiceResult.unsupported(String.format("unknown service 0x%04X", serviceId));
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

        // The status travels to the VM in R0, but the reason stays host-side.
        // Log it (lazy: the supplier is not called unless debug is enabled) so
        // failures are diagnosable without changing the ABI.
        if (result.isError() && result.hasDetail()) {
            Logger.debug(() -> String.format("HOST 0x%04X -> %s: %s",
                serviceId, result.status(), result.detail()));
        }

        return result;
    }

    // ===== Service implementations =====

    private ServiceResult doPropertyRead(ExecutionContext ctx, int argVAddr, int argLength, int options) {
        int scope = (options >> 8) & 0xFF;
        int propId = options & 0x00FF;

        String value = ctx.hostContext().readProperty(scope, propId).orElse(null);

        // Host-side enrichment (design.md §10.1): resolve placeholders when a
        // resolver is injected, and auto-detect a missing source language.
        try {
            if (scope == SCOPE_MESSAGE && propId == PROP_LANG_SOURCE
                    && (value == null || value.isEmpty()) && translationDetector != null) {
                String content = ctx.hostContext().readProperty(SCOPE_MESSAGE, PROP_CONTENT).orElse(null);
                if (content != null) {
                    String detected = translationDetector.apply(content);
                    if (detected != null && !detected.isEmpty()) {
                        value = detected;
                    }
                }
            }
            if (value != null && scope == SCOPE_MESSAGE && propId == PROP_CONTENT
                    && placeholderResolver != null) {
                value = placeholderResolver.apply(value);
            }
        } catch (RuntimeException e) {
            return ServiceResult.hostFailure("Property enrichment failed: " + e.getMessage());
        }

        if (value == null) {
            return ServiceResult.notFound(
                String.format("no property scope=%d id=0x%02X", scope, propId));
        }

        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > argLength) {
            return ServiceResult.bufferTooSmall(bytes.length);
        }

        try {
            ctx.dma().writeBlock(argVAddr, bytes, 0, bytes.length);
        } catch (VmException e) {
            return ServiceResult.hostFailure("Memory write failed: " + e.getMessage());
        }

        return ServiceResult.ok(bytes.length);
    }

    /**
     * Applies a host transformation to the buffer text, selected by the low byte
     * of {@code options}: {@link #TRANSFORM_RESOLVE_PLACEHOLDERS} resolves external
     * placeholders and {@link #TRANSFORM_DETECT_LANGUAGE} detects the language.
     * The result replaces the input in place. Returns {@code UNSUPPORTED} when the
     * host did not inject the hook for the requested kind.
     */
    private ServiceResult doTransformApply(ExecutionContext ctx, int argVAddr, int argLength, int options) {
        int kind = options & 0x00FF;
        java.util.function.Function<String, String> transform = switch (kind) {
            case TRANSFORM_RESOLVE_PLACEHOLDERS -> placeholderResolver;
            case TRANSFORM_DETECT_LANGUAGE -> translationDetector;
            default -> null;
        };
        if (transform == null) {
            return ServiceResult.unsupported("transform not configured: kind=" + kind);
        }

        byte[] bytes;
        try {
            bytes = ctx.dma().getRegion(argVAddr, argLength);
        } catch (VmException e) {
            return ServiceResult.hostFailure("Memory read failed: " + e.getMessage());
        }

        String output;
        try {
            output = transform.apply(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return ServiceResult.hostFailure("Transform failed: " + e.getMessage());
        }
        if (output == null) {
            return ServiceResult.notFound("transform produced no value: kind=" + kind);
        }

        byte[] out = output.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (out.length > argLength) {
            return ServiceResult.bufferTooSmall(out.length);
        }
        try {
            ctx.dma().writeBlock(argVAddr, out, 0, out.length);
        } catch (VmException e) {
            return ServiceResult.hostFailure("Memory write failed: " + e.getMessage());
        }
        return ServiceResult.ok(out.length);
    }

    private ServiceResult doPropertyWrite(ExecutionContext ctx, int argVAddr, int argLength, int options) {
        int scope = (options >> 8) & 0xFF;
        int propId = options & 0x00FF;

        byte[] bytes;
        try {
            bytes = ctx.dma().getRegion(argVAddr, argLength);
        } catch (VmException e) {
            return ServiceResult.hostFailure("Memory read failed: " + e.getMessage());
        }

        String value = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);

        // The host decides how to apply the write (the reference engine defers it
        // through the modification journal, which is validated and committed after
        // execution). Unsupported (scope, propId) pairs are reported to the program.
        if (!ctx.hostContext().writeProperty(scope, propId, value)) {
            return ServiceResult.unsupported(
                "property not writable: scope=" + scope + " prop=" + propId);
        }

        return ServiceResult.ok(argLength);
    }

    private ServiceResult doPermissionCheck(ExecutionContext ctx, int argVAddr, int argLength, int options) {
        if (permissionChecker == null) {
            return ServiceResult.hostFailure("Permission checker not configured");
        }

        byte[] bytes;
        try {
            bytes = ctx.dma().getRegion(argVAddr, argLength);
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
                bytes = ctx.dma().getRegion(argVAddr, argLength);
            } catch (VmException e) {
                return ServiceResult.hostFailure("Memory read failed: " + e.getMessage());
            }
            redirectChannel = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            bytesRead = argLength;
        } else if (decision == DECISION_REDIRECT && argLength > 0) {
            // REDIRECT: read target actor from argument buffer
            byte[] bytes;
            try {
                bytes = ctx.dma().getRegion(argVAddr, argLength);
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
                bytes = ctx.dma().getRegion(argVAddr, argLength);
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
        Integer key = options & 0xFFFF;
        Object value = ctx.hostContext().readState(key).orElse(null);
        if (value == null) {
            return ServiceResult.notFound("state key=" + key);
        }
        String str = value.toString();
        byte[] bytes = str.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > argLength) {
            return ServiceResult.bufferTooSmall(bytes.length);
        }
        try {
            ctx.dma().writeBlock(argVAddr, bytes, 0, bytes.length);
        } catch (VmException e) {
            return ServiceResult.hostFailure("Memory write failed: " + e.getMessage());
        }
        return ServiceResult.ok(bytes.length);
    }

    private ServiceResult doStateWrite(ExecutionContext ctx, int argVAddr, int argLength, int options) {
        Integer key = options & 0xFFFF;
        byte[] bytes;
        try {
            bytes = ctx.dma().getRegion(argVAddr, argLength);
        } catch (VmException e) {
            return ServiceResult.hostFailure("Memory read failed: " + e.getMessage());
        }
        String value = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        ctx.hostContext().writeState(key, value);
        return ServiceResult.ok(argLength);
    }

    private ServiceResult doChannelResolve(ExecutionContext ctx, int argVAddr, int argLength, int options) {
        byte[] bytes;
        try {
            bytes = ctx.dma().getRegion(argVAddr, argLength);
        } catch (VmException e) {
            return ServiceResult.hostFailure("Memory read failed: " + e.getMessage());
        }
        String channelName = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        String channelId = ctx.hostContext().resolveChannel(channelName).orElse(null);
        if (channelId == null) {
            return ServiceResult.notFound("unknown channel: " + channelName);
        }
        byte[] resultBytes = channelId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (resultBytes.length > argLength) {
            return ServiceResult.bufferTooSmall(resultBytes.length);
        }
        try {
            ctx.dma().writeBlock(argVAddr, resultBytes, 0, resultBytes.length);
        } catch (VmException e) {
            return ServiceResult.hostFailure("Memory write failed: " + e.getMessage());
        }
        return ServiceResult.ok(resultBytes.length);
    }

    /**
     * PERMISSION_QUERY: same {@code actor\0node} argument as PERMISSION_CHECK but
     * reports the outcome through the status ({@code OK}/{@code DENIED}) so the
     * program can branch on {@code R0}; the boolean still travels in {@code R4}.
     */
    private ServiceResult doPermissionQuery(ExecutionContext ctx, int argVAddr, int argLength, int options) {
        if (permissionChecker == null) {
            return ServiceResult.hostFailure("Permission checker not configured");
        }
        byte[] bytes;
        try {
            bytes = ctx.dma().getRegion(argVAddr, argLength);
        } catch (VmException e) {
            return ServiceResult.hostFailure("Memory read failed: " + e.getMessage());
        }
        String data = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        String[] parts = data.split("\0", 2);
        if (parts.length < 2) {
            return ServiceResult.invalidArgument("Invalid permission query args");
        }
        boolean allowed = permissionChecker.apply(parts[0], parts[1]);
        return allowed ? ServiceResult.ok(argLength, 1) : ServiceResult.denied("permission not granted");
    }

    /**
     * ROUTE_REDIRECT: journals a redirect to the actor/channel named in the buffer.
     * The low byte of {@code options} selects the kind
     * ({@link #REDIRECT_KIND_ACTOR}/{@link #REDIRECT_KIND_CHANNEL}).
     */
    private ServiceResult doRouteRedirect(ExecutionContext ctx, int argVAddr, int argLength, int options) {
        int kind = options & 0x00FF;
        if (argLength <= 0) {
            return ServiceResult.invalidArgument("redirect requires a target");
        }
        byte[] bytes;
        try {
            bytes = ctx.dma().getRegion(argVAddr, argLength);
        } catch (VmException e) {
            return ServiceResult.hostFailure("Memory read failed: " + e.getMessage());
        }
        String target = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        if (target.isEmpty()) {
            return ServiceResult.invalidArgument("redirect target is empty");
        }
        ModificationJournal.OpType type = kind == REDIRECT_KIND_CHANNEL
            ? ModificationJournal.OpType.REDIRECT_CHANNEL
            : ModificationJournal.OpType.REDIRECT_TARGET;
        ctx.journal().record(new ModificationJournal.Entry(type, target, "routeRedirect:" + kind));
        return ServiceResult.ok(argLength, kind);
    }

    /**
     * ROUTE_RATE_LIMIT: computes the backoff for the key in the buffer through the
     * injected {@link me.majhrs16.suite.iflow.channel.RateLimiter} and journals it.
     * Without a limiter the service is {@code UNSUPPORTED}.
     */
    private ServiceResult doRouteRateLimit(ExecutionContext ctx, int argVAddr, int argLength, int options) {
        if (rateLimiter == null) {
            return ServiceResult.unsupported("rate limiter not configured");
        }
        byte[] bytes;
        try {
            bytes = ctx.dma().getRegion(argVAddr, argLength);
        } catch (VmException e) {
            return ServiceResult.hostFailure("Memory read failed: " + e.getMessage());
        }
        String key = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        long backoff = rateLimiter.nanosUntilNextWindow(key) / 1_000_000L; // nanos -> millis
        if (backoff < 0) {
            backoff = 0;
        }
        ctx.journal().record(new ModificationJournal.Entry(
            ModificationJournal.OpType.RATE_LIMIT_BACKOFF, backoff, "routeRateLimit"));
        return ServiceResult.ok(argLength, (int) Math.min(backoff, Integer.MAX_VALUE));
    }

    /**
     * CHANNEL_POLICY: reads a named channel's policy (send/receive/rate, selected
     * by the low byte of {@code options}) and writes it into the buffer.
     */
    private ServiceResult doChannelPolicy(ExecutionContext ctx, int argVAddr, int argLength, int options) {
        int policy = options & 0x00FF;
        if (argLength <= 0) {
            return ServiceResult.invalidArgument("channel policy requires a channel name");
        }
        int propId = switch (policy) {
            case CHANNEL_POLICY_SEND -> PROP_CHAN_SEND_POL;
            case CHANNEL_POLICY_RECEIVE -> PROP_CHAN_RECV_POL;
            case CHANNEL_POLICY_RATE -> PROP_CHAN_RATE;
            default -> -1;
        };
        if (propId < 0) {
            return ServiceResult.invalidArgument("unknown channel policy: " + policy);
        }
        byte[] bytes;
        try {
            bytes = ctx.dma().getRegion(argVAddr, argLength);
        } catch (VmException e) {
            return ServiceResult.hostFailure("Memory read failed: " + e.getMessage());
        }
        // The buffer carries the channel name as a C string, so R3 can also be
        // used as the output capacity (the policy is written over the name).
        String channelName = cString(bytes);
        String value = ctx.hostContext().readChannelProperty(channelName, propId).orElse(null);
        if (value == null) {
            return ServiceResult.notFound("unknown channel or policy: " + channelName);
        }
        byte[] out = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (out.length > argLength) {
            return ServiceResult.bufferTooSmall(out.length);
        }
        try {
            ctx.dma().writeBlock(argVAddr, out, 0, out.length);
        } catch (VmException e) {
            return ServiceResult.hostFailure("Memory write failed: " + e.getMessage());
        }
        return ServiceResult.ok(out.length);
    }

    /**
     * MESSAGE_CLONE: copies {@code argLength} bytes of the message's serialized
     * input representation ({@code INPUT_BASE}) into the destination buffer through
     * the DMA, giving the program a private copy of the message to transform.
     */
    private ServiceResult doMessageClone(ExecutionContext ctx, int argVAddr, int argLength, int options) {
        int size = ctx.ioBufferSize();
        if (argLength <= 0) {
            return ServiceResult.invalidArgument("clone length must be positive");
        }
        if (argLength > size) {
            return ServiceResult.invalidArgument(
                "clone length exceeds the input buffer: " + argLength + " > " + size);
        }
        try {
            byte[] payload = ctx.dma().getRegion(ctx.inputBufferVAddr(), argLength);
            ctx.dma().writeBlock(argVAddr, payload, 0, argLength);
        } catch (VmException e) {
            return ServiceResult.hostFailure("Memory transfer failed: " + e.getMessage());
        }
        return ServiceResult.ok(argLength);
    }

    /** MESSAGE_ID: writes the current message id into the buffer. */
    private ServiceResult doMessageId(ExecutionContext ctx, int argVAddr, int argLength, int options) {
        String id = ctx.messageId();
        if (id == null || id.isEmpty()) {
            return ServiceResult.notFound("message id unavailable");
        }
        byte[] bytes = id.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > argLength) {
            return ServiceResult.bufferTooSmall(bytes.length);
        }
        try {
            ctx.dma().writeBlock(argVAddr, bytes, 0, bytes.length);
        } catch (VmException e) {
            return ServiceResult.hostFailure("Memory write failed: " + e.getMessage());
        }
        return ServiceResult.ok(bytes.length);
    }

    /** Decodes a NUL-terminated C string from a (possibly longer) byte buffer. */
    private static String cString(byte[] bytes) {
        int end = 0;
        while (end < bytes.length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, 0, end, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ===== Host dependency injection =====

    public HostServiceRegistry permissionChecker(BiFunction<String, String, Boolean> checker) {
        this.permissionChecker = checker;
        return this;
    }

    /**
     * Injects the placeholder resolver (host binds the actor). Enables
     * TRANSFORM_APPLY kind 0 and placeholder resolution in PROPERTY_READ content.
     */
    public HostServiceRegistry placeholderResolver(java.util.function.Function<String, String> resolver) {
        this.placeholderResolver = resolver;
        return this;
    }

    /**
     * Injects the language detector. Enables TRANSFORM_APPLY kind 1 and the
     * auto-detection of an unset source language in PROPERTY_READ.
     */
    public HostServiceRegistry translationDetector(java.util.function.Function<String, String> detector) {
        this.translationDetector = detector;
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

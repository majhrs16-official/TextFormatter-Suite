package me.majhrs16.suite.syncbus;

import me.majhrs16.suite.api.pipeline.OutputPlan;
import me.majhrs16.suite.api.pipeline.PipelineContext;
import me.majhrs16.suite.api.pipeline.PipelineMessage;
import me.majhrs16.suite.api.pipeline.PipelineScope;
import me.majhrs16.suite.api.pipeline.PipelineStage;
import me.majhrs16.suite.api.pipeline.StageResult;
import me.majhrs16.suite.api.spi.SyncBus;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Terminal {@code sync} stage: fans the delivered message out to sync sinks.
 *
 * <p>It runs once per emission, in {@link PipelineScope#MESSAGE_POST}, after
 * the platform deliveries. Sinks are discovered by the bus; the stage never
 * enumerates or orders them. A declared {@link OutputPlan} limits the fan-out
 * to the {@code sync.<backend>} targets it names; an inherited plan keeps the
 * existing broadcast-to-all behaviour.</p>
 *
 * <p>The stage does not alter the message. A sink failure is the bus's
 * responsibility and never stops the rest of the pipeline.</p>
 */
public final class SyncStage implements PipelineStage {

    /** Contract id of the terminal stage. */
    public static final String ID = "sync";

    private static final String MULTIPLEXER = "sync";

    private final SyncBus bus;

    public SyncStage(SyncBus bus) {
        this.bus = Objects.requireNonNull(bus, "bus");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<PipelineScope> scopes() {
        return Set.of(PipelineScope.MESSAGE_POST);
    }

    @Override
    public StageResult afterMessage(PipelineMessage message, PipelineContext context) {
        OutputPlan outputs = context.outputs();
        if (!outputs.declared()) {
            int accepted = bus.broadcast(message.message());
            if (accepted == 0) {
                context.logger().debug("sync stage: no sink accepted the message");
            }
            return StageResult.continueWith(message);
        }

        List<String> backends = syncBackends(outputs.targets());
        if (backends.isEmpty()) {
            return StageResult.notApplicable(message);
        }
        boolean any = false;
        for (String backend : backends) {
            if (bus.sendTo(backend, message.message())) {
                any = true;
            } else {
                context.logger().debug("sync backend '" + backend + "' is not available");
            }
        }
        return any ? StageResult.continueWith(message) : StageResult.notApplicable(message);
    }

    /** @return backend names named by {@code sync.<backend>} targets, in order */
    static List<String> syncBackends(List<String> targets) {
        return targets.stream()
                .filter(target -> target.startsWith(MULTIPLEXER + "."))
                .map(target -> target.substring(MULTIPLEXER.length() + 1))
                .filter(backend -> !backend.isBlank())
                .toList();
    }
}

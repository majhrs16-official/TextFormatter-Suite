package me.majhrs16.suite.syncbus;

import me.majhrs16.suite.api.pipeline.DeliveryEnvelope;
import me.majhrs16.suite.api.pipeline.OutputOutcome;
import me.majhrs16.suite.api.pipeline.OutputPlan;
import me.majhrs16.suite.api.pipeline.PipelineContext;
import me.majhrs16.suite.api.pipeline.PipelineMessage;
import me.majhrs16.suite.api.pipeline.PipelineScope;
import me.majhrs16.suite.api.pipeline.PipelineStage;
import me.majhrs16.suite.api.pipeline.StageResult;
import me.majhrs16.suite.api.spi.SyncBus;
import me.majhrs16.suite.api.sync.SyncResult;
import me.majhrs16.suite.api.sync.SyncSurface;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Terminal {@code sync} stage: fans the delivered message out to sync sinks.
 *
 * <p>It runs once per emission, in {@link PipelineScope#MESSAGE_POST}, after
 * the platform deliveries. Sinks are discovered by the bus; the stage never
 * enumerates or orders them. A declared {@link OutputPlan} limits the fan-out
 * to the {@code sync.<backend>} targets it names, in declaration order, and
 * reports one {@link OutputOutcome} per target; an inherited plan keeps the
 * existing broadcast-to-all behaviour.</p>
 *
 * <p>The stage does not alter the message. A sink failure is the bus's
 * responsibility, is exposed in the aggregate result and never stops the rest
 * of the pipeline.</p>
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
        DeliveryEnvelope envelope = new DeliveryEnvelope(
                message.message(), message.deliveries());
        OutputPlan outputs = context.outputs();
        if (!outputs.declared()) {
            List<SyncResult> results = bus.deliver(envelope);
            List<SyncSurface> surfaces = List.copyOf(bus.surfaces());
            List<OutputOutcome> inherited = new ArrayList<>(results.size());
            boolean any = false;
            for (int index = 0; index < results.size(); index++) {
                String target = index < surfaces.size()
                        ? MULTIPLEXER + "." + surfaces.get(index).backend()
                        : MULTIPLEXER;
                SyncResult result = results.get(index);
                inherited.add(new OutputOutcome(target, result));
                any |= result.isOk();
            }
            if (!any) {
                context.logger().debug("sync stage: no backend delivered the envelope");
            }
            return StageResult.continueWith(message, inherited);
        }

        List<String> targets = syncTargets(outputs.targets());
        if (targets.isEmpty()) {
            return StageResult.notApplicable(message);
        }
        List<String> backends = syncBackends(targets);
        List<SyncResult> results = bus.deliverTo(
                backends, envelope, outputs.parallel());

        List<OutputOutcome> outcomes = new ArrayList<>(targets.size());
        boolean any = false;
        for (int index = 0; index < targets.size(); index++) {
            SyncResult result = index < results.size()
                    ? results.get(index)
                    : new SyncResult.HostFailure(
                            "sync bus returned no result for target '" + targets.get(index) + "'");
            outcomes.add(new OutputOutcome(targets.get(index), result));
            if (result.isOk()) {
                any = true;
            } else {
                context.logger().debug("sync target '" + targets.get(index)
                        + "' did not deliver the message: " + result);
            }
        }
        return any
                ? StageResult.continueWith(message, outcomes)
                : StageResult.notApplicable(message, outcomes);
    }

    /** @return declared {@code sync.<backend>} targets, in order */
    static List<String> syncTargets(List<String> targets) {
        return targets.stream()
                .filter(target -> target.startsWith(MULTIPLEXER + "."))
                .toList();
    }

    /** @return backend names named by {@code sync.<backend>} targets, in order */
    static List<String> syncBackends(List<String> targets) {
        return syncTargets(targets).stream()
                .map(target -> target.substring(MULTIPLEXER.length() + 1))
                .filter(backend -> !backend.isBlank())
                .toList();
    }
}

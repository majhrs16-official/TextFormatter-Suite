package me.majhrs16.suite.host;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.pipeline.DeliverySummary;
import me.majhrs16.suite.api.pipeline.DeliveryUnit;
import me.majhrs16.suite.api.pipeline.OutputOutcome;
import me.majhrs16.suite.api.pipeline.OutputPlan;
import me.majhrs16.suite.api.pipeline.PipelineContext;
import me.majhrs16.suite.api.pipeline.PipelineMessage;
import me.majhrs16.suite.api.pipeline.PipelinePlan;
import me.majhrs16.suite.api.pipeline.PipelineScope;
import me.majhrs16.suite.api.pipeline.PipelineStatus;
import me.majhrs16.suite.api.pipeline.PipelineTrace;
import me.majhrs16.suite.api.pipeline.StageResult;
import me.majhrs16.suite.api.pipeline.StageStatus;
import me.majhrs16.suite.api.pipeline.StageTrace;
import me.majhrs16.suite.api.render.ComponentRenderedOutput;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.api.sync.SyncResult;

import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executes the ordered pipeline: phases, per-recipient stages, delivery and
 * trace.
 *
 * <p>Recipient expansion is delegated to {@link RecipientResolver}. The engine
 * renders one unit per recipient and hands the envelope to the terminal
 * {@code sync} stage; physical delivery belongs to the local sink through the
 * {@link me.majhrs16.suite.api.spi.LocalDelivery} port. Stages are the only
 * source of transformation, so the order is exactly the plan order and never
 * the module graph order.</p>
 *
 * <p>The engine is rebuilt when the plan changes; in-flight dispatches keep
 * the engine (and plan version) they started with.</p>
 */
public final class PipelineEngine implements AutoCloseable {

    private static final int TRACE_CAPACITY = 256;
    private static final int WORKER_THREADS = 4;
    private static final int MAX_WORKER_THREADS = 32;
    private static final int WORK_QUEUE_CAPACITY = 1000;
    private static final long RECIPIENT_TIMEOUT_SECONDS = 10;

    private final PipelinePlan plan;
    private final List<ResolvedStage> stages;
    private final List<SkippedStage> skipped;
    private final RecipientResolver recipients;
    private final PipelineEnvironment environment;
    private final ExecutorService workers;
    private final long planVersion;
    private volatile long warnedPlanVersion = Long.MIN_VALUE;

    public PipelineEngine(PipelinePlan plan,
                          List<ResolvedStage> stages,
                          List<SkippedStage> skipped,
                          RecipientResolver recipients,
                          PipelineEnvironment environment,
                          long planVersion) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.stages = List.copyOf(Objects.requireNonNull(stages, "stages"));
        verifyOrder(plan, this.stages);
        this.skipped = List.copyOf(Objects.requireNonNull(skipped, "skipped"));
        this.recipients = Objects.requireNonNull(recipients, "recipients");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.planVersion = planVersion;
        this.workers = new ThreadPoolExecutor(
                WORKER_THREADS, MAX_WORKER_THREADS, 60L, TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(WORK_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "pipeline-engine-worker");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /** @return the plan version this engine executes */
    public long planVersion() {
        return planVersion;
    }

    /** Executes one emission end-to-end. */
    public PipelineOutcome dispatch(Message message) {
        Objects.requireNonNull(message, "message");
        PipelineTrace trace = new BoundedPipelineTrace(TRACE_CAPACITY);
        PipelineStatus status = stages.isEmpty() ? PipelineStatus.NO_PIPELINE : plan.status();

        if (status == PipelineStatus.NO_PIPELINE) {
            warnNoPipeline();
            return PipelineOutcome.none(status, trace, "no pipeline");
        }
        if (message.isCancelled()) {
            return PipelineOutcome.none(status, trace, "cancelled");
        }

        recordSkipped(trace);
        Message resolved = resolveSourceLanguage(message);
        OutputPlanResolver.Resolution resolution = OutputPlanResolver.resolveDetailed(
                resolved.outputPlan(), environment.outputs());
        OutputPlan outputs = resolution.plan();
        recordOutputOrigin(trace, resolution);
        PipelineMessage emission = PipelineMessage.of(resolved);

        StageResult pre = runScope(PipelineScope.MESSAGE_PRE, emission, trace, outputs);
        if (pre.terminal()) {
            return terminalOutcome(status, trace, pre);
        }
        emission = pre.message();

        List<Actor> expanded = recipients.resolve(emission.message());
        RecipientCounters counters = deliverToRecipients(emission, expanded, trace, outputs);
        emission = emission.withDeliveries(counters.units);

        StageResult post = runScope(
                PipelineScope.MESSAGE_POST, emission, trace, outputs);
        boolean cancelEvent = emission.cancelEvent() || post.message().cancelEvent()
                || counters.cancelEvent;

        DeliverySummary delivered = summarize(post.outputs());
        List<OutputOutcome> outputResults = new ArrayList<>();
        outputResults.addAll(pre.outputs());
        outputResults.addAll(counters.outputs);
        outputResults.addAll(post.outputs());

        return new PipelineOutcome(status, expanded.size(),
                counters.delivered + delivered.delivered(),
                counters.silenced + delivered.silenced(),
                counters.redirected + delivered.redirected(),
                counters.failed + delivered.failed(),
                cancelEvent, outputResults, trace, null);
    }

    private RecipientCounters deliverToRecipients(PipelineMessage emission,
                                               List<Actor> expanded,
                                               PipelineTrace trace,
                                               OutputPlan outputs) {
        if (expanded.isEmpty()) {
            return new RecipientCounters();
        }
        if (!environment.parallel() || expanded.size() == 1) {
            RecipientCounters counters = new RecipientCounters();
            for (Actor recipient : expanded) {
                merge(counters, deliverTo(emission, recipient, trace, outputs));
            }
            return counters;
        }

        List<CompletableFuture<RecipientOutcome>> futures = new ArrayList<>(expanded.size());
        for (Actor recipient : expanded) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> deliverTo(emission, recipient, trace, outputs), workers));
        }

        RecipientCounters counters = new RecipientCounters();
        for (CompletableFuture<RecipientOutcome> future : futures) {
            try {
                merge(counters, future.get(RECIPIENT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            } catch (TimeoutException timeout) {
                future.cancel(true);
                counters.failed++;
                environment.logger().warn("recipient pipeline timed out after "
                        + RECIPIENT_TIMEOUT_SECONDS + "s");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                counters.failed++;
                break;
            } catch (Exception failure) {
                counters.failed++;
                environment.logger().error("recipient pipeline failed", failure);
            }
        }
        return counters;
    }

    private RecipientOutcome deliverTo(PipelineMessage emission,
                                       Actor recipient,
                                       PipelineTrace trace,
                                       OutputPlan outputs) {
        PipelineMessage unit = emission.withRecipient(recipient);
        StageResult result = runScope(PipelineScope.RECIPIENT, unit, trace, outputs);
        if (result.terminal()) {
            boolean cancel = result.message().cancelEvent();
            return switch (result.kind()) {
                case DROP, REJECT -> RecipientOutcome.silenced(cancel, result.outputs());
                case ERROR -> RecipientOutcome.failed(cancel, result.outputs());
                case CONTINUE, NOT_APPLICABLE -> throw new IllegalStateException(
                        "terminal result with non-terminal kind: " + result.kind());
            };
        }
        unit = ensureRendered(result.message());
        DeliveryUnit rendered = new DeliveryUnit(
                recipient, unit.decision(), unit.message(), unit.rendered());
        return RecipientOutcome.rendered(unit.cancelEvent(), result.outputs(), rendered);
    }

    private PipelineMessage ensureRendered(PipelineMessage unit) {
        if (unit.rendered() != null) {
            return unit;
        }
        String text = unit.message().text();
        return unit.withRendered(new ComponentRenderedOutput(Component.text(text), text));
    }

    private StageResult runScope(PipelineScope scope,
                                 PipelineMessage unit,
                                 PipelineTrace trace,
                                 OutputPlan outputs) {
        PipelineMessage current = unit;
        List<OutputOutcome> collected = new ArrayList<>();
        for (ResolvedStage stage : stages) {
            if (!stage.scopes().contains(scope)) {
                continue;
            }
            PipelineContext context = new DefaultPipelineContext(
                    current.message().id().toString(), scope, environment, outputs,
                    trace, planVersion);
            StageResult result = invoke(stage, scope, current, context);
            collected.addAll(result.outputs());
            if (result.terminal()) {
                return result.withOutputs(collected);
            }
            current = result.message();
        }
        return StageResult.continueWith(current, collected);
    }

    private StageResult invoke(ResolvedStage stage,
                               PipelineScope scope,
                               PipelineMessage unit,
                               PipelineContext context) {
        long start = System.nanoTime();
        StageResult result;
        try {
            result = switch (scope) {
                case MESSAGE_PRE -> stage.stage().beforeMessage(unit, context);
                case RECIPIENT -> stage.stage().perRecipient(unit, context);
                case MESSAGE_POST -> stage.stage().afterMessage(unit, context);
            };
            if (result == null) {
                result = StageResult.error(unit, "stage returned null");
            }
        } catch (RuntimeException failure) {
            result = StageResult.error(unit,
                    failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
        context.trace().record(new StageTrace(
                stage.stageId(),
                stage.moduleId(),
                scope,
                unit.recipient(),
                result.traceStatus(),
                result.detail(),
                System.nanoTime() - start));
        return result;
    }

    /**
     * Rejects resolved stages that the plan does not contain or that invert its
     * order.
     *
     * <p>Missing stages are allowed: the plan builder records them as
     * {@link SkippedStage}. A present-but-misordered stage means the resolver and
     * the plan disagree, so the engine refuses to start instead of silently
     * running a different order.</p>
     */
    private static void verifyOrder(PipelinePlan plan, List<ResolvedStage> stages) {
        int cursor = 0;
        for (ResolvedStage stage : stages) {
            int index = plan.stageIds().indexOf(stage.stageId());
            if (index < 0) {
                throw new IllegalArgumentException("stage '" + stage.stageId()
                        + "' is not part of plan version " + plan.version());
            }
            if (index < cursor) {
                throw new IllegalArgumentException("stage '" + stage.stageId()
                        + "' is out of plan order");
            }
            cursor = index;
        }
    }

    /**
     * Records where the effective output plan came from. The entry uses the
     * synthetic id {@code outputs} and is not a pipeline stage.
     */
    private static void recordOutputOrigin(PipelineTrace trace,
                                           OutputPlanResolver.Resolution resolution) {
        trace.record(new StageTrace(
                "outputs", null, null, null,
                StageStatus.RUN, resolution.origin().wireName(), 0L));
    }

    private void recordSkipped(PipelineTrace trace) {
        for (SkippedStage absent : skipped) {
            trace.record(new StageTrace(
                    absent.stageId(), null, null, null,
                    StageStatus.SKIPPED, absent.detail(), 0L));
        }
    }

    private PipelineOutcome terminalOutcome(PipelineStatus status,
                                            PipelineTrace trace,
                                            StageResult result) {
        return switch (result.kind()) {
            case DROP, REJECT -> new PipelineOutcome(
                    status, 0, 0, 1, 0, 0,
                    result.message().cancelEvent(), result.outputs(), trace, result.detail());
            case ERROR -> new PipelineOutcome(
                    status, 0, 0, 0, 0, 1,
                    result.message().cancelEvent(), result.outputs(), trace, result.detail());
            case CONTINUE, NOT_APPLICABLE -> throw new IllegalStateException(
                    "non-terminal result in terminal outcome");
        };
    }

    private Message resolveSourceLanguage(Message message) {
        if (message.resolvedSourceLanguage() != null) {
            return message;
        }
        Language resolved;
        if (message.langSource() != null && message.langSource() != Language.AUTO) {
            resolved = message.langSource();
        } else {
            Optional<TranslationService> translation = environment.translation()
                    .filter(TranslationService::isAvailable);
            if (translation.isPresent()) {
                Language detected = translation.get().detect(message.text());
                resolved = detected == Language.AUTO
                        ? environment.settings().defaultLanguage()
                        : detected;
            } else {
                resolved = environment.settings().defaultLanguage();
            }
        }
        return message.withResolvedSourceLanguage(resolved);
    }

    private void warnNoPipeline() {
        if (warnedPlanVersion == planVersion) {
            return;
        }
        warnedPlanVersion = planVersion;
        environment.logger().warn(
                "Message pipeline has no effective stages (plan version " + planVersion
                        + "); dispatch returns NO_PIPELINE");
    }

    private static void merge(RecipientCounters counters, RecipientOutcome outcome) {
        counters.cancelEvent |= outcome.cancelEvent();
        counters.outputs.addAll(outcome.outputs());
        switch (outcome.kind()) {
            case RENDERED -> counters.units.add(outcome.rendered());
            case SILENCED -> counters.silenced++;
            case FAILED -> counters.failed++;
        }
    }

    /**
     * Sums the delivery summaries reported by the local sinks. External sinks
     * return {@code Ok(null)}, so they never contribute to the recipient
     * counters.
     */
    private static DeliverySummary summarize(List<OutputOutcome> outputs) {
        int delivered = 0;
        int silenced = 0;
        int redirected = 0;
        int failed = 0;
        for (OutputOutcome output : outputs) {
            if (output.result() instanceof SyncResult.Ok ok
                    && ok.value() instanceof DeliverySummary summary) {
                delivered += summary.delivered();
                silenced += summary.silenced();
                redirected += summary.redirected();
                failed += summary.failed();
            }
        }
        return new DeliverySummary(delivered, silenced, redirected, failed);
    }

    @Override
    public void close() {
        workers.shutdown();
        try {
            if (!workers.awaitTermination(RECIPIENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private record RecipientOutcome(Kind kind, boolean cancelEvent,
                                    List<OutputOutcome> outputs,
                                    DeliveryUnit rendered) {

        enum Kind { RENDERED, SILENCED, FAILED }

        RecipientOutcome {
            outputs = List.copyOf(outputs);
        }

        static RecipientOutcome rendered(boolean cancel, List<OutputOutcome> outputs,
                                         DeliveryUnit rendered) {
            return new RecipientOutcome(Kind.RENDERED, cancel, outputs, rendered);
        }

        static RecipientOutcome silenced(boolean cancel, List<OutputOutcome> outputs) {
            return new RecipientOutcome(Kind.SILENCED, cancel, outputs, null);
        }

        static RecipientOutcome failed(boolean cancel, List<OutputOutcome> outputs) {
            return new RecipientOutcome(Kind.FAILED, cancel, outputs, null);
        }
    }

    private static final class RecipientCounters {
        int delivered;
        int silenced;
        int redirected;
        int failed;
        boolean cancelEvent;
        final List<OutputOutcome> outputs = new ArrayList<>();
        final List<DeliveryUnit> units = new ArrayList<>();
    }
}

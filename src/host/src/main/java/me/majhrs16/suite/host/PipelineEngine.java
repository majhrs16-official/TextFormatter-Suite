package me.majhrs16.suite.host;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
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
 * <p>Recipient expansion is delegated to {@link RecipientResolver} and platform
 * output to {@link DeliveryExecutor}. Stages are the only source of
 * transformation, so the order is exactly the plan order and never the module
 * graph order.</p>
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
    private final DeliveryExecutor delivery;
    private final PipelineEnvironment environment;
    private final ExecutorService workers;
    private final long planVersion;
    private volatile long warnedPlanVersion = Long.MIN_VALUE;

    public PipelineEngine(PipelinePlan plan,
                          List<ResolvedStage> stages,
                          List<SkippedStage> skipped,
                          RecipientResolver recipients,
                          DeliveryExecutor delivery,
                          PipelineEnvironment environment,
                          long planVersion) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.stages = List.copyOf(Objects.requireNonNull(stages, "stages"));
        verifyOrder(plan, this.stages);
        this.skipped = List.copyOf(Objects.requireNonNull(skipped, "skipped"));
        this.recipients = Objects.requireNonNull(recipients, "recipients");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
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
        PipelineMessage emission = PipelineMessage.of(resolved);

        StageResult pre = runScope(PipelineScope.MESSAGE_PRE, emission, trace);
        if (pre.terminal()) {
            return terminalOutcome(status, trace, pre);
        }
        emission = pre.message();

        List<Actor> expanded = recipients.resolve(emission.message());
        RecipientCounters counters = deliverToRecipients(emission, expanded, trace);

        PipelineMessage post = runScope(PipelineScope.MESSAGE_POST, emission, trace).message();
        boolean cancelEvent = emission.cancelEvent() || post.cancelEvent()
                || counters.cancelEvent;

        return new PipelineOutcome(status, expanded.size(), counters.delivered,
                counters.silenced, counters.redirected, counters.failed,
                cancelEvent, trace, null);
    }

    private RecipientCounters deliverToRecipients(PipelineMessage emission,
                                               List<Actor> expanded,
                                               PipelineTrace trace) {
        if (expanded.isEmpty()) {
            return new RecipientCounters();
        }
        if (!environment.parallel() || expanded.size() == 1) {
            RecipientCounters counters = new RecipientCounters();
            for (Actor recipient : expanded) {
                merge(counters, deliverTo(emission, recipient, trace));
            }
            return counters;
        }

        List<CompletableFuture<RecipientOutcome>> futures = new ArrayList<>(expanded.size());
        for (Actor recipient : expanded) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> deliverTo(emission, recipient, trace), workers));
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
                                       PipelineTrace trace) {
        PipelineMessage unit = emission.withRecipient(recipient);
        StageResult result = runScope(PipelineScope.RECIPIENT, unit, trace);
        if (result.terminal()) {
            boolean cancel = result.message().cancelEvent();
            return switch (result.kind()) {
                case DROP, REJECT -> RecipientOutcome.silenced(cancel);
                case ERROR -> RecipientOutcome.failed(cancel);
                case CONTINUE, NOT_APPLICABLE -> throw new IllegalStateException(
                        "terminal result with non-terminal kind: " + result.kind());
            };
        }
        unit = ensureRendered(result.message());
        boolean cancel = unit.cancelEvent();
        try {
            return switch (delivery.deliver(recipient, unit).kind()) {
                case DELIVERED -> RecipientOutcome.delivered(cancel);
                case SILENCED -> RecipientOutcome.silenced(cancel);
                case REDIRECTED -> RecipientOutcome.redirected(cancel);
                case FAILED -> RecipientOutcome.failed(cancel);
            };
        } catch (RuntimeException failure) {
            environment.logger().error("delivery failed for " + recipient.name(), failure);
            return RecipientOutcome.failed(cancel);
        }
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
                                 PipelineTrace trace) {
        PipelineMessage current = unit;
        for (ResolvedStage stage : stages) {
            if (!stage.scopes().contains(scope)) {
                continue;
            }
            PipelineContext context = new DefaultPipelineContext(
                    current.message().id().toString(), scope, environment, trace, planVersion);
            StageResult result = invoke(stage, scope, current, context);
            if (result.terminal()) {
                return result;
            }
            current = result.message();
        }
        return StageResult.continueWith(current);
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
                    result.message().cancelEvent(), trace, result.detail());
            case ERROR -> new PipelineOutcome(
                    status, 0, 0, 0, 0, 1,
                    result.message().cancelEvent(), trace, result.detail());
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
        switch (outcome.kind()) {
            case DELIVERED -> counters.delivered++;
            case SILENCED -> counters.silenced++;
            case REDIRECTED -> counters.redirected++;
            case FAILED -> counters.failed++;
        }
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

    private record RecipientOutcome(Kind kind, boolean cancelEvent) {

        enum Kind { DELIVERED, SILENCED, REDIRECTED, FAILED }

        static RecipientOutcome delivered(boolean cancel) {
            return new RecipientOutcome(Kind.DELIVERED, cancel);
        }

        static RecipientOutcome silenced(boolean cancel) {
            return new RecipientOutcome(Kind.SILENCED, cancel);
        }

        static RecipientOutcome redirected(boolean cancel) {
            return new RecipientOutcome(Kind.REDIRECTED, cancel);
        }

        static RecipientOutcome failed(boolean cancel) {
            return new RecipientOutcome(Kind.FAILED, cancel);
        }
    }

    private static final class RecipientCounters {
        int delivered;
        int silenced;
        int redirected;
        int failed;
        boolean cancelEvent;
    }
}

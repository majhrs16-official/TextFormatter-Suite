package me.majhrs16.suite.iflow.vm.engine;

import me.majhrs16.suite.iflow.RouteDecision;
import me.majhrs16.suite.iflow.RouteOutcome;
import me.majhrs16.suite.iflow.target.PolicyTarget;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;
import me.majhrs16.suite.iflow.vm.execution.ExecutionContext;
import me.majhrs16.suite.iflow.vm.execution.ModificationJournal;
import me.majhrs16.suite.iflow.vm.isa.VmException;

/**
 * Validates the VM execution result and converts it to a RouteOutcome.
 * <p>
 * Ensures the VM-produced decision is valid and applies the modification journal
 * to produce the final message for delivery.
 * </p>
 */
public final class PolicyResultValidator {

    private final ChannelRegistry channels;

    public PolicyResultValidator(ChannelRegistry channels) {
        this.channels = channels;
    }

    /**
     * Validates the VM execution result and produces a RouteOutcome.
     *
     * @param context the execution context after VM execution
     * @param originalMessage the original message before routing
     * @param recipient the recipient actor
     * @param emitter the emitter actor
     * @return the validated route outcome
     */
    public RouteOutcome validate(ExecutionContext context, Message originalMessage,
                                  Actor recipient, Actor emitter) {

        ModificationJournal journal = context.journal();

        // Check for fatal execution error
        if (context.hasFatalError()) {
            VmException err = context.fatalError();
            // Execution failed - discard journal, return REJECT
            journal.discard();
            return RouteOutcome.of(
                new RouteDecision(PolicyTarget.REJECT,
                    "VM execution failed: " + err.getMessage(), 0, recipient, emitter),
                originalMessage
            );
        }

        // Check if cancelled via journal
        if (journal.entries().stream().anyMatch(e -> e.type == ModificationJournal.OpType.CANCEL_MESSAGE)) {
            journal.discard();
            return RouteOutcome.of(
                new RouteDecision(PolicyTarget.DROP, "Cancelled by policy", 0, recipient, emitter),
                originalMessage
            );
        }

        // Extract routing decision from journal
        PolicyTarget target = PolicyTarget.LOG;
        String reason = "policy";
        long backoffMillis = 0;
        String redirectChannel = null;

        for (ModificationJournal.Entry entry : journal.entries()) {
            switch (entry.type) {
                case SET_PROCESSED -> {
                    // Check detail for routing decision
                    String detail = entry.detail;
                    if (detail != null && detail.startsWith("route:")) {
                        String route = detail.substring(6);
                        target = parseRouteDecision(route);
                        reason = "route:" + route;
                    }
                }
                case REDIRECT_CHANNEL -> {
                    target = PolicyTarget.CHANNEL_REDIRECT;
                    redirectChannel = (String) entry.value;
                    reason = "redirect to " + redirectChannel;
                }
                case CANCEL_MESSAGE -> {
                    target = PolicyTarget.DROP;
                    reason = "cancelled";
                }
            }
        }

        // Validate redirect channel exists
        if (target == PolicyTarget.CHANNEL_REDIRECT && redirectChannel != null) {
            // Check if the channel actually exists in the registry (not just resolves to default)
            if (!channels.paths().contains(redirectChannel) && !channels.has(redirectChannel)) {
                // Invalid channel - fall back to REJECT
                journal.discard();
                return RouteOutcome.of(
                    new RouteDecision(PolicyTarget.REJECT,
                        "Invalid redirect channel: " + redirectChannel, 0, recipient, emitter),
                    originalMessage
                );
            }
        }

        // Apply journal modifications to message
        Message transformedMessage = applyJournal(originalMessage, journal);

        // Commit journal on success
        journal.commit();

        return RouteOutcome.of(
            new RouteDecision(target, reason, backoffMillis, recipient, emitter, redirectChannel),
            transformedMessage
        );
    }

    private PolicyTarget parseRouteDecision(String route) {
        return switch (route.toUpperCase()) {
            case "LOG" -> PolicyTarget.LOG;
            case "DROP" -> PolicyTarget.DROP;
            case "REJECT" -> PolicyTarget.REJECT;
            case "REDIRECT" -> PolicyTarget.REDIRECT;
            case "CHANNEL_REDIRECT" -> PolicyTarget.CHANNEL_REDIRECT;
            case "RATE_LIMIT" -> PolicyTarget.RATE_LIMIT;
            case "CONTINUE" -> PolicyTarget.LOG; // Map to LOG
            default -> PolicyTarget.LOG;
        };
    }

    private Message applyJournal(Message original, ModificationJournal journal) {
        Message working = original;
        for (ModificationJournal.Entry entry : journal.entries()) {
            working = applyEntry(working, entry);
        }
        return working;
    }

    private Message applyEntry(Message msg, ModificationJournal.Entry entry) {
        return switch (entry.type) {
            case SET_TEXT -> msg.withText((String) entry.value);
            case SET_CHANNEL -> msg.withChannel((String) entry.value);
            case SET_LANG_SOURCE -> msg.withLangSource(
                me.majhrs16.suite.api.message.Language.of((String) entry.value).orElse(msg.langSource()));
            case SET_LANG_TARGET -> msg.withLangTarget(
                me.majhrs16.suite.api.message.Language.of((String) entry.value).orElse(msg.langTarget()));
            case SET_COLOR_MODE -> msg.withColorMode(
                me.majhrs16.suite.api.message.ColorMode.valueOf(((String) entry.value).toUpperCase()));
            case SET_FORMAT_PAPI -> msg.withFormatPapi((Boolean) entry.value);
            case SET_SLEEP_MILLIS -> msg.withSleepMillis((Long) entry.value);
            case ADD_SOUNDS -> msg.withSoundsAdd((java.util.List<String>) entry.value);
            case REMOVE_SOUNDS -> msg.withSoundsRemove((java.util.List<String>) entry.value);
            case CANCEL_MESSAGE -> msg.withCancelled(true);
            case SET_PROCESSED -> msg.withProcessed(true);
            case REDIRECT_CHANNEL -> msg; // Handled separately
            default -> msg;
        };
    }
}
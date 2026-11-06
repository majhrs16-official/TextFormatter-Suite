package me.majhrs16.suite.api.pipeline;

import java.util.List;
import java.util.Optional;

/**
 * Read-only view of the neutral services available to a stage.
 *
 * <p>It deliberately hides registration: a stage consumes services, it does
 * not publish them. Lookups are exact-type lookups, like the runtime's
 * {@code ServiceRegistry}.</p>
 */
public interface PipelineServices {

    /** @return the first service registered under {@code type}, if any */
    <T> Optional<T> find(Class<T> type);

    /** @return every service registered under {@code type}, in order */
    <T> List<T> findAll(Class<T> type);
}

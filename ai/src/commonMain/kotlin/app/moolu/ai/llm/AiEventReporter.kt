package app.moolu.ai.llm

/**
 * Pluggable observation hook for [LlmProvider] implementations.
 *
 * Mirrors the role of [app.moolu.foundation.logging.MooluLogger]'s
 * structured-log surface but keeps a tighter shape so V0.5 doesn't have
 * to ship a dependency on Sentry-KMP yet (that's plan-21 / Wave 6
 * platform-crash-analytics in the master plan).
 *
 * **What does NOT go through this interface**:
 * - Prompt content / completion text — privacy boundary; never logged.
 * - User identifiers — same.
 *
 * Only metadata is reported: throwable type, endpoint URL, requested
 * model name. plan-21 wires [captureLlmError] to
 * `Sentry.captureException(...)` with extra tags `{endpoint, model}`.
 */
fun interface AiEventReporter {
    /**
     * Called by [RemoteLlmProvider] when a stream / complete request
     * fails (network error, 4xx/5xx, parse error, cancellation already
     * filtered out by the caller).
     *
     * @param throwable the failure cause; pass straight to Sentry
     * @param endpoint full URL that was requested
     * @param model the requested model id, if specified in [GenOptions]
     */
    fun captureLlmError(
        throwable: Throwable,
        endpoint: String,
        model: String?,
    )

    /** Production V0.5 default: drop on the floor. plan-21 swaps to Sentry. */
    object NoOp : AiEventReporter {
        override fun captureLlmError(
            throwable: Throwable,
            endpoint: String,
            model: String?,
        ) {
            // Intentionally empty — see KDoc.
        }
    }
}

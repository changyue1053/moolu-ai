/*
 * Cite: ADR-34 (Tier 3 moolu-ai · `ResponseStream` agentic UI hook public surface
 *               implementation) ·
 *       ADR-33 (anti-wheel client 版 · R-NO-WHEEL-1 network · 0 direct Ktor SSE
 *               import in NEW response/ package) ·
 *       spec §11.5.4 Flow 1 α' agentic streaming 8 步 verbatim ·
 *       spec §16.2 P6 (KMP ABI lock · Konsist ≥ 60).
 *
 * Stage 0 CSO D-N INLINE absorption (per task-6.13-cso-threat-model.md §5):
 * - **D-3 (HIGH · OWASP A04 + STRIDE D+T · per Task 6.11 D-3 +
 *   Task 6.12 D-3 SingleFlight pattern generalize)**: cancel idempotent ·
 *   `Mutex.withLock` atomic state transition · `MutableStateFlow<Boolean>`
 *   provides thread-safe state read · multiple cancel calls no-op after first
 *   transition
 * - **D-8 (HIGH · spec §11.1 anti-wheel + R-NO-WHEEL-1 network)**:
 *   ResponseStreamImpl consumes the upstream Flow<ResponseChunk> source ·
 *   factory at `app.moolu.ai.response.sseResponseStream(...)` consumes
 *   moolu-network `serverSentEvents()` extension · 0 direct Ktor SSE import
 *   in this internal package
 *
 * Phase 6 Cluster 4 Task 6.13 NEW · KMP commonMain · internal visibility.
 */
package app.moolu.ai.response.internal

import app.moolu.ai.response.ResponseChunk
import app.moolu.ai.response.ResponseStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Default [ResponseStream] implementation backed by a cold [Flow] of
 * [ResponseChunk] events from the upstream ai-gateway.
 *
 * **Stage 0 D-3 INLINE** (per Task 6.11 D-3 + Task 6.12 D-3 SingleFlight
 * pattern generalize · OWASP A04 + STRIDE D+T): [cancel] is idempotent ·
 * `Mutex.withLock` atomic state transition · multiple concurrent calls
 * collapse to one effective transition · subsequent calls no-op without
 * leaking SSE Job references.
 *
 * **Stage 0 D-8 INLINE** (anti-wheel R-NO-WHEEL-1 network): the [source]
 * parameter receives a Flow<ResponseChunk> typically constructed via
 * [app.moolu.ai.response.sseResponseStream] which internally consumes
 * `app.moolu.network.sse.serverSentEvents()` extension fun · this class itself
 * has 0 direct Ktor SSE imports.
 *
 * **Cancellation semantics** (per kotlinx-coroutines Flow + structured
 * concurrency contract): [observe] uses `takeWhile { isActive }` consumer-side
 * check so emissions stop within one upstream chunk after [cancel] · downstream
 * collectors observe Flow completion (NOT exception) on cancel.
 *
 * @param source upstream chunk source (typically constructed via
 *     [app.moolu.ai.response.sseResponseStream] factory · supplied raw Flow
 *     for direct testing with `flowOf(...)` fixtures)
 */
internal class ResponseStreamImpl(
    private val source: Flow<ResponseChunk>,
) : ResponseStream {
    private val mutex = Mutex()
    private val cancelledState = MutableStateFlow(false)

    /**
     * Public read-only state · downstream observers can subscribe to cancel
     * transitions if needed (NOT exposed on [ResponseStream] interface · use
     * [ResponseStream.isActive] for synchronous reads).
     */
    internal val cancelled = cancelledState.asStateFlow()

    override fun observe(): Flow<ResponseChunk> = source.takeWhile { !cancelledState.value }

    override suspend fun cancel() {
        mutex.withLock {
            if (cancelledState.value) return@withLock
            cancelledState.value = true
        }
    }

    override val isActive: Boolean
        get() = !cancelledState.value
}

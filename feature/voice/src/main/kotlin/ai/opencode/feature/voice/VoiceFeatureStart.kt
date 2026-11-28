package ai.opencode.feature.voice

import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.cancellable
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.friendlyName
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

// Starting a call on its route — Server Voice's probe, the phone key's preflight, then the session.

fun VoiceFeature.State.setActiveRoute(
    route: VoiceRoute,
    usesDelegator: Boolean,
    usesSessionDelegator: Boolean = false,
): VoiceFeature.State = copy(
    activeRoute = route,
    usesDelegator = usesDelegator,
    usesSessionDelegator = usesSessionDelegator,
)

internal fun VoiceFeature.startCall(state: VoiceFeature.State): Reduced<VoiceFeature.State, VoiceFeature.Action> {
    var next = state
    val target = next.target
    val selectedEngine = next.resolvedEngine
    val openAIKey = next.callAPIKey(VoiceEngine.gptLive)?.trim() ?: ""
    val xaiKey = next.callAPIKey(VoiceEngine.grok)?.trim() ?: ""
    if (next.voiceServer == VoiceServerChoice.Connected) {
        shared(VoiceSharedKeys.voiceServerChoice).update { it.pinningConnected(target.server) }
    }
    val saved = VoiceFeature.savedServers
    val (broker, readiness) = VoiceBroker.resolve(
        choice = next.voiceServer,
        provider = next.serverVoiceProvider,
        connected = target.server,
        saved = saved,
        answers = next.voiceModelsByServer,
        support = next.serverVoiceSupportByServer,
        hasOpenAIKey = openAIKey.isNotEmpty(),
        engine = selectedEngine,
    )
    val serverVoiceEnabled = broker.isEnabled
    // Only the broker moves; the call's session, context and tools stay on its own server.
    val brokerServer = broker.server ?: target.server
    if (serverVoiceEnabled && brokerServer.persistenceID != next.supportServer.persistenceID) {
        // What the call knew of another server's support says nothing of this one's.
        next = next.copy(
            serverVoiceBroker = if (brokerServer.persistenceID == target.server.persistenceID) null else brokerServer,
            serverVoiceSupport = ServerVoiceSupport.unknown,
        )
    }
    val previousSupport = next.serverVoiceSupport
    val previousCached = next.serverVoiceSupportByServer[brokerServer.persistenceID]
    val initialSupport = if (previousSupport != ServerVoiceSupport.unknown) previousSupport else previousCached ?: ServerVoiceSupport.unknown
    val resolve: (VoiceBroker, ServerVoiceSupport) -> VoiceRoute = { routeBroker, support ->
        routeBroker.route(selectedEngine, support, hasOpenAIKey = openAIKey.isNotEmpty(), hasXAIKey = xaiKey.isNotEmpty())
    }

    /** Automatic, once `server` turns out to have no voice routes: the next provider that can place the call. A pick never moves. */
    val movedOn: (OpenCodeServer) -> VoiceBroker? = movedOn@{ server ->
        if (!broker.isAutomatic) return@movedOn null
        VoiceBroker(
            choice = VoiceServerChoice.Automatic,
            connected = target.server,
            saved = saved,
            readiness = readiness.copy(support = readiness.support + (server.persistenceID to ServerVoiceSupport.unsupported)),
        )
    }
    val place: (VoiceBroker) -> VoiceCallPlace? = place@{ placeBroker ->
        val server = placeBroker.server
        if (server == null || selectedEngine == VoiceEngine.grok) return@place null
        VoiceCallPlace(name = server.friendlyName, brand = readiness.brand(server, placeBroker.provider))
    }
    val route = resolve(broker, initialSupport)
    next = next.copy(diagnosesFailure = selectedEngine != VoiceEngine.grok, callPlace = null)
    val shouldProbe = serverVoiceEnabled && selectedEngine != VoiceEngine.grok && initialSupport != ServerVoiceSupport.supported
    // No provider key anywhere, but a speech server is set: this phone can run the call itself.
    // A draft's words go to the session its first request creates. After a server switch the
    // screen can still draw a session whose identity has not landed: fall back to the session
    // the call was last in, then one it has visited.
    val screenSessionID = target.sessionID
        ?: next.draftSessionID
        ?: next.lastMove?.sessionID
        ?: next.visitedSessionIDs.lastOrNull()
    val localSessionID = screenSessionID?.takeIf { ai.opencode.core.sharing.shared(ai.opencode.core.sharing.SharedKeys.voiceSpeechServer).value.isNotBlank() }
    if (route is VoiceRoute.Unavailable && !shouldProbe && localSessionID == null) {
        next = next.setActiveRoute(route, usesDelegator = false).copy(callPlace = place(broker))
        return Reduced(next.withPhase(VoiceFeature.State.Phase.Failed(route.reason.message)))
    }
    val key = if (selectedEngine == VoiceEngine.grok) xaiKey else openAIKey
    val sounds = dependency(VoiceCallSounds)
    sounds.voice(VoiceCallHaptic.callStarted)
    val voiceCallLog = dependency(VoiceCallLogClient)
    val uuid = dependency(UuidClient)
    next = next.copy(callLogID = if (voiceCallLog.recorder == null) null else uuid.next())
        .withPhase(VoiceFeature.State.Phase.Connecting)
    AppLog.voice.notice("Call starting")
    val voiceDelegator = dependency(VoiceDelegatorClient)
    // Every call opens a fresh, unmuted microphone.
    next = next.copy(
        isMuted = false,
        isAudioInterrupted = false,
        isReconnecting = false,
        isAssistantSpeaking = false,
        connectedAt = null,
        callNote = null,
    ).clearLiveReply().copy(
        adoptedDraftSessionID = null,
        // A new call explains itself to each session again.
        handoffs = emptyMap(),
        sessionDelegator = next.sessionDelegator.resetForCallStart(),
        // What this conversation said so far is an earlier call's now.
        previousCallTail = next.tail.lines,
        visitedSessionIDs = emptyList(),
        lastMove = null,
        userLooking = false,
        startedTarget = target,
        startedViewing = next.viewing,
    )
    val delegatorEnabled = voiceDelegator.isEnabled && key != "lab"
    val experimentalFeatures = dependency(ExperimentalFeatures)
    val sessionDelegatorEligible = experimentalFeatures(ExperimentalFeature.voiceSessionDelegator) &&
        next.voiceDelegatorChoice.usesOpenCodeSession &&
        key != "lab"
    val usesSessionDelegatorForRoute: (VoiceRoute) -> Boolean = { resolvedRoute ->
        sessionDelegatorEligible && resolvedRoute.engine == VoiceEngine.gptLive
    }
    val usesBuiltInDelegatorForRoute: (VoiceRoute) -> Boolean = { resolvedRoute ->
        !usesSessionDelegatorForRoute(resolvedRoute) && resolvedRoute.engine == VoiceEngine.gptLive && delegatorEnabled
    }
    next = next.setActiveRoute(
        route,
        usesDelegator = usesBuiltInDelegatorForRoute(route),
        usesSessionDelegator = usesSessionDelegatorForRoute(route),
    ).copy(callPlace = if (route.isServer) place(broker) else null)
    val voice = next.callVoice.resolved(selectedEngine)
    // On Server Voice with Built-in, OpenAI runs the delegator on the server's
    // key (Responses delegation); with OpenCode Session or a phone key, the
    // phone runs client delegation.
    next = next.copy(
        activeVoice = voice,
        delegatorResponseID = null,
        // Android: the call's ongoing notification, started with the call.
        callPresenceID = uuid.next(),
    )
    val initialContext = next.instructionsContext
    val responsesDelegation: LiveDelegation? = if (selectedEngine == VoiceEngine.gptLive && key != "lab") {
        LiveDelegation.responses(VoiceDelegator.responsesDelegation(context = delegatorContext(next, otherRequests = 0)))
    } else {
        null
    }
    next.atEarBox.current = next.isAtEar
    val atEarBox = next.atEarBox
    // A key Settings already verified for this model skips the preflight
    // (a ~0.7 s round trip, measured); a new one is still checked first.
    val verified = key.isNotEmpty() && next.verifiedKeyFingerprint == VoiceKeyFingerprint.make(key = key, model = selectedEngine.modelID)
    val startsAtEar = next.isAtEar
    val voiceAudio = dependency(VoiceAudioClient)
    val serverVoice = dependency(ServerVoiceClient)
    val realtimePreflight = dependency(RealtimePreflightClient)
    val voiceCall = dependency(VoiceCallClient)

    val effect = Effect.run<VoiceFeature.Action> { send ->
        // The call's audio starts with the tap, alongside the probe, the
        // preflight, and the context below; the call adopts it once set up.
        val preparedAudio = voiceAudio.prepare(startsAtEar)
        var discarded = false
        suspend fun discardPrepared() {
            if (discarded) return
            discarded = true
            withContext(NonCancellable) { voiceAudio.discardPrepared(preparedAudio) }
        }
        try {
            var context = initialContext
            var resolvedRoute = route
            var callBroker = brokerServer

            // Automatic moves on from a server without the voice routes: to the
            // next ready server, else the phone's key.
            suspend fun moveOn(server: OpenCodeServer) {
                val moved = movedOn(server)
                val nextServer = moved?.server
                if (moved == null || !moved.isEnabled || nextServer == null || nextServer.persistenceID == server.persistenceID) {
                    // This phone's key, else nothing can: the server's own failure says why.
                    resolvedRoute = resolve(broker, ServerVoiceSupport.unsupported)
                    if (resolvedRoute is VoiceRoute.PhoneKey) {
                        send(VoiceFeature.Action.CallPlaceChanged(null))
                    } else {
                        resolvedRoute = VoiceRoute.Unavailable(VoiceRoute.Reason.ServerUnsupported)
                    }
                    return
                }
                callBroker = nextServer
                resolvedRoute = resolve(moved, readiness.support[nextServer.persistenceID] ?: ServerVoiceSupport.unknown)
                send(
                    VoiceFeature.Action.CallBrokerMoved(
                        server = if (nextServer.persistenceID == target.server.persistenceID) null else nextServer,
                        place = place(moved),
                    ),
                )
            }

            suspend fun resolvedAgain() {
                val useBuiltIn = usesBuiltInDelegatorForRoute(resolvedRoute)
                val useSession = usesSessionDelegatorForRoute(resolvedRoute)
                send(VoiceFeature.Action.CallRouteResolved(resolvedRoute, usesDelegator = useBuiltIn, usesSessionDelegator = useSession))
                context = context.copy(managesSessions = useBuiltIn || useSession, recordsLooking = useBuiltIn)
            }

            if (shouldProbe) {
                val probed = serverVoice.checkSupport(brokerServer)
                if (!currentCoroutineContext().isActive) {
                    discardPrepared()
                    return@run
                }
                if ((probed == ServerVoiceSupport.supported || probed == ServerVoiceSupport.unsupported) &&
                    (previousSupport != probed || previousCached != probed)
                ) {
                    send(VoiceFeature.Action.ServerVoiceSupportUpdated(probed))
                }
                resolvedRoute = resolve(broker, probed)
                if (probed == ServerVoiceSupport.unsupported && broker.isAutomatic) moveOn(brokerServer)
                resolvedAgain()
            }

            suspend fun startWithPhoneKey(context: VoiceInstructions.Context): Flow<VoiceCallEvent> {
                if (key.isEmpty()) throw RealtimePreflightError.Service(VoiceRoute.Reason.MissingPhoneKey(selectedEngine).message)
                if (!verified) realtimePreflight.verify(key, selectedEngine.modelID)
                currentCoroutineContext().ensureActive()
                val phoneRoute = VoiceRoute.PhoneKey(selectedEngine)
                val config = phoneRoute.sessionConfig(
                    voice = voice, context = context, isAtEar = atEarBox.current, server = target.server,
                    unboundSpeechUsesInstructions = usesSessionDelegatorForRoute(phoneRoute),
                )
                return voiceCall.start(key, config)
            }

            suspend fun startOnServerOrFallBack(route: VoiceRoute, server: OpenCodeServer): Flow<VoiceCallEvent> {
                val useSession = usesSessionDelegatorForRoute(route)
                val delegation = if (!useSession && usesBuiltInDelegatorForRoute(route)) {
                    responsesDelegation ?: LiveDelegation.client
                } else {
                    LiveDelegation.client
                }
                val config = route.sessionConfig(
                    voice = voice, context = context, isAtEar = atEarBox.current,
                    server = server, delegation = delegation,
                    unboundSpeechUsesInstructions = useSession,
                )
                try {
                    return voiceCall.start("", config)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    val hostedByOpenCode = readiness.brand(server, route.provider) ==
                        VoiceProviderOption.Brand.openCode
                    val fallbackDelegation = responsesDelegation
                    if (!useSession ||
                        route != VoiceRoute.serverOpenAI(VoiceRoute.OpenAI.gptLive) ||
                        fallbackDelegation == null ||
                        !VoiceFeature.shouldFallBackFromClientDelegation(error, hostedByOpenCode)
                    ) {
                        throw error
                    }
                    AppLog.voice.notice("Server voice rejected client delegation; falling back to built-in delegator for this call")
                    send(
                        VoiceFeature.Action.CallRouteResolved(
                            route,
                            usesDelegator = true,
                            usesSessionDelegator = false,
                            note = VoiceFeature.builtInFallbackNote,
                        ),
                    )
                    context = context.copy(managesSessions = true, recordsLooking = true)
                    val fallbackConfig = route.sessionConfig(
                        voice = voice, context = context, isAtEar = atEarBox.current,
                        server = server, delegation = fallbackDelegation,
                        unboundSpeechUsesInstructions = false,
                    )
                    return voiceCall.start("", fallbackConfig)
                }
            }

            suspend fun startLocal(server: OpenCodeServer): Flow<VoiceCallEvent>? {
                val id = localSessionID ?: return null
                return voiceCall.start(
                    "",
                    RealtimeSessionConfig(
                        engine = selectedEngine,
                        model = "local",
                        voice = voice,
                        instructions = "",
                        tools = emptyList(),
                        isAtEar = atEarBox.current,
                        serverVoice = server,
                        localSessionID = id,
                    ),
                )
            }

            // The server says it can place calls but has no key for them (a 5xx): this phone does.
            suspend fun startOnServer(route: VoiceRoute, server: OpenCodeServer): Flow<VoiceCallEvent> = try {
                startOnServerOrFallBack(route, server)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                startLocal(server) ?: throw error
            }

            // In parallel when using a phone key: the preflight, and the session's
            // recent messages, which wait at most 0.6 s rather than hold the call back.
            val needsPreflight = resolvedRoute == VoiceRoute.PhoneKey(selectedEngine) && !verified
            val started: Pair<Flow<VoiceCallEvent>?, Exception?> = coroutineScope {
                val preflight = if (needsPreflight) {
                    async {
                        try {
                            realtimePreflight.verify(key, selectedEngine.modelID)
                            null
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            error
                        }
                    }
                } else {
                    null
                }
                context = context.copy(recentMessages = fetchRecentMessages(target))
                try {
                    currentCoroutineContext().ensureActive()
                    // Server Voice pins one broker; a call on another server would prompt that
                    // other server's session ID and be refused (404 / "no key"). Run it here.
                    val localHere = if (localSessionID != null && brokerServer.persistenceID != target.server.persistenceID) {
                        startLocal(target.server)
                    } else {
                        null
                    }
                    val events = localHere ?: when (val chosen = resolvedRoute) {
                        is VoiceRoute.Server -> {
                            try {
                                startOnServer(chosen, callBroker)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                if (chosen.provider != ServerVoiceProvider.openAI || !broker.isAutomatic ||
                                    (error.localizedMessage ?: error.message) != ServerVoiceError.unsupportedServerNotice
                                ) {
                                    throw error
                                }
                                send(VoiceFeature.Action.ServerVoiceSupportUpdated(ServerVoiceSupport.unsupported))
                                moveOn(callBroker)
                                resolvedAgain()
                                when (val moved = resolvedRoute) {
                                    is VoiceRoute.Server -> startOnServer(moved, callBroker)
                                    is VoiceRoute.PhoneKey -> startWithPhoneKey(context)
                                    is VoiceRoute.Unavailable -> throw RealtimePreflightError.Service(moved.reason.message)
                                }
                            }
                        }
                        is VoiceRoute.PhoneKey -> {
                            preflight?.await()?.let { throw it }
                            currentCoroutineContext().ensureActive()
                            val config = chosen.sessionConfig(
                                voice = voice, context = context, isAtEar = atEarBox.current, server = target.server,
                                unboundSpeechUsesInstructions = usesSessionDelegatorForRoute(chosen),
                            )
                            voiceCall.start(key, config)
                        }
                        is VoiceRoute.Unavailable -> {
                            val localID = localSessionID ?: throw RealtimePreflightError.Service(chosen.reason.message)
                            voiceCall.start(
                                "",
                                RealtimeSessionConfig(
                                    engine = selectedEngine,
                                    model = "local",
                                    voice = voice,
                                    instructions = "",
                                    tools = emptyList(),
                                    isAtEar = atEarBox.current,
                                    serverVoice = target.server,
                                    localSessionID = localID,
                                ),
                            )
                        }
                    }
                    events to null
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    preflight?.cancel()
                    null to error
                }
            }
            val events = started.first
            if (events == null) {
                val error = started.second
                discardPrepared()
                // Cancelled by a hang-up, which ends it, or by a newer start whose tap must stay.
                if (currentCoroutineContext().isActive) sounds.cancelPendingStart()
                send(VoiceFeature.Action.CallEnded(errorMessage = error?.localizedMessage ?: error?.toString()))
                return@run
            }
            // Adopted by the call; anything left wasn't (a scripted call).
            discardPrepared()
            try {
                events.collect { event -> send(VoiceFeature.Action.CallEvent(event)) }
                send(VoiceFeature.Action.CallEnded(errorMessage = null))
            } catch (error: CancellationException) {
                throw error
            } catch (_: RealtimeClientError.Dropped) {
                send(VoiceFeature.Action.CallDropped)
            } catch (error: Exception) {
                send(VoiceFeature.Action.CallEnded(errorMessage = error.localizedMessage ?: error.toString()))
            }
        } catch (error: CancellationException) {
            discardPrepared()
            throw error
        }
    }.cancellable(VoiceFeature.CancelID.Call, cancelInFlight = true)
    return Reduced(next, effect)
}

/**
 * Whether a failed Server Voice GPT-Live start with `delegation: client` was rejected for client
 * delegation (such as an OpenCode Console proxy that only accepts `responses` delegation) rather
 * than an auth, billing, missing key, or network failure.
 */
fun VoiceFeature.Companion.shouldFallBackFromClientDelegation(error: Throwable, hostedByOpenCode: Boolean): Boolean {
    if (error is CancellationException) return false
    if (error is IOException) return false
    val message = error.localizedMessage ?: error.message ?: error.toString()
    if (message == ServerVoiceError.unsupportedServerNotice) return false
    if (message.contains("delegation", ignoreCase = true)) return true
    if (!hostedByOpenCode) return false
    val nonSchemaTokens = listOf(
        "api key", "401", "402", "403", "429", "503",
        "unauthorized", "forbidden", "rate limit", "quota", "billing", "credit",
        "offline", "timed out", "network connection",
    )
    if (nonSchemaTokens.any { message.contains(it, ignoreCase = true) }) return false
    val schemaTokens = listOf(
        "invalid", "unsupported", "unrecognized", "validation",
        "schema", "bad request", "unprocessable", "http 400", "http 422",
    )
    return schemaTokens.any { message.contains(it, ignoreCase = true) }
}

/**
 * Existing reducer/route callers share the full debug-only lab policy.
 */
internal typealias VoiceLabFlags = VoiceLabDemo

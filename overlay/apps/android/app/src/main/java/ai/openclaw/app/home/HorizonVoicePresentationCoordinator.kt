package ai.openclaw.app.home

import kotlinx.serialization.json.JsonObject

/**
 * Converts a deliberately small set of completed spoken requests into a local Horizon card.
 *
 * Presentation only: this class never changes the voice answer, gateway request, agent session,
 * or home state. Normal question cards receive their TTL after audio drained. A morning briefing
 * is a foreground-driven visual sequence over already hydrated dashboard facts.
 */
internal class HorizonVoicePresentationCoordinator(
  private val repository: HorizonPresentationRepository,
  private val diagnostic: (String) -> Unit = {},
  private val briefingFacts: () -> HorizonMorningBriefingFacts = { HorizonMorningBriefingFacts() },
  private val nowMs: () -> Long = System::currentTimeMillis,
) {
  private data class Pending(
    val kind: HorizonTileType,
    val ttlMs: Long,
    val id: String,
    val utteranceKey: String,
    var responseComplete: Boolean = false,
    var speechDrained: Boolean = false,
  )

  private enum class BriefingStep(val type: HorizonTileType, val ttlMs: Long) {
    Weather(HorizonTileType.Weather, WEATHER_TTL_MS),
    Tasks(HorizonTileType.Tasks, TASKS_TTL_MS),
    Music(HorizonTileType.Music, MUSIC_TTL_MS),
  }

  private data class MorningBriefing(
    val id: Long,
    var responseComplete: Boolean = false,
    var finalSegmentSeen: Boolean = false,
    var speechDrained: Boolean = false,
    var stage: BriefingStep = BriefingStep.Weather,
    var deadlineMs: Long? = null,
  )

  private var pending: Pending? = null
  private var briefing: MorningBriefing? = null

  @Synchronized
  fun onFinalUserTranscript(text: String) {
    val normalized = normalize(text)
    if (normalized in MORNING_BRIEFINGS) {
      if (briefing != null) return
      cancelMorningBriefing(clearVisible = true)
      pending = null
      briefing = MorningBriefing(id = nextBriefingId++)
      diagnostic("briefing-arm")
      return
    }

    // A new user intent owns the surface over any briefing. Tool updates from the same
    // briefing are intentionally not routed here and therefore do not tear it down.
    cancelMorningBriefing(clearVisible = true)
    if (pending?.utteranceKey == normalized) return
    val intent = parseIntent(normalized)
    pending = intent?.let { (kind, ttlMs) ->
      Pending(kind = kind, ttlMs = ttlMs, id = "voice-${kind.name.lowercase()}-${nextId++}", utteranceKey = normalized)
    }
    diagnostic(pending?.let { "arm kind=${it.kind}" } ?: "clear no-local-intent")
  }

  /** The accepted agent lifecycle ended; no visual TTL starts until speech has drained. */
  @Synchronized
  fun onAgentCompleted() {
    pending?.let {
      it.responseComplete = true
      it.speechDrained = false
      diagnostic("complete kind=${it.kind}")
    }
    briefing?.let {
      // A drain before lifecycle terminal belonged to a progress/filler sentence.
      // Preserve only a drain observed after terminal ownership is established.
      it.speechDrained = false
      it.responseComplete = true
      diagnostic("briefing-complete")
    }
    publishIfReady()
    startBriefingIfReady()
  }

  /** Direct replies have no agent lifecycle; their final transcript is completion. */
  @Synchronized
  fun onDirectAssistantTranscriptCompleted() {
    pending?.responseComplete = true
    pending?.let { diagnostic("direct-complete kind=${it.kind}") }
    briefing?.let {
      it.responseComplete = true
      diagnostic("briefing-direct-complete")
    }
    publishIfReady()
    startBriefingIfReady()
  }

  /** Final transcript callback; ignored until the accepted lifecycle has completed. */
  @Synchronized
  fun onFinalAssistantSegment() {
    val flow = briefing ?: return
    if (!flow.responseComplete) return
    flow.finalSegmentSeen = true
    diagnostic("briefing-final-segment")
    startBriefingIfReady()
  }

  /** Called only at the local audio playhead drain boundary. */
  @Synchronized
  fun onAssistantSpeechDrained() {
    pending?.speechDrained = true
    pending?.let { diagnostic("drain kind=${it.kind}") }
    publishIfReady()

    val flow = briefing ?: return
    // Keep the physical boundary for a terminal final delivered slightly later. Agent completion
    // clears pre-terminal progress drains above; direct replies have no such lifecycle boundary.
    flow.speechDrained = true
    startBriefingIfReady()
  }

  /**
   * Called by the existing foreground Horizon tick. It performs at most one transition per call;
   * no timer or background service is created for briefing presentation.
   */
  @Synchronized
  fun advance(nowMs: Long) {
    val flow = briefing ?: return
    val deadline = flow.deadlineMs ?: return
    if (!flow.speechDrained || nowMs < deadline) return
    when (flow.stage) {
      BriefingStep.Weather -> {
        flow.stage = BriefingStep.Tasks
        flow.deadlineMs = nowMs + TASKS_TTL_MS
        showBriefingStepIfFresh(flow, BriefingStep.Tasks, nowMs)
      }
      BriefingStep.Tasks -> {
        flow.stage = BriefingStep.Music
        if (hasFreshFacts(BriefingStep.Music)) {
          flow.deadlineMs = nowMs + MUSIC_TTL_MS
          showBriefingStepIfFresh(flow, BriefingStep.Music, nowMs)
        } else {
          diagnostic("briefing-skip kind=${BriefingStep.Music.type}")
          briefing = null
        }
      }
      BriefingStep.Music -> {
        briefing = null
        diagnostic("briefing-finish")
      }
    }
  }

  /** Explicit UI, urgent Ring/timer state, or a new user turn may call this. */
  @Synchronized
  fun cancelMorningBriefing() = cancelMorningBriefing(clearVisible = true)

  /**
   * The conversation close does not erase an already accepted/draining sequence: it can outlive
   * the 20-second voice window. A close before a final accepted reply cannot leave a blind timer.
   */
  @Synchronized
  fun onVoiceConversationClosed() {
    pending = null
    val flow = briefing ?: return
    if (!flow.responseComplete || !flow.finalSegmentSeen) cancelMorningBriefing(clearVisible = true)
  }

  @Synchronized
  fun clearPending() = onVoiceConversationClosed()

  private fun startBriefingIfReady() {
    val flow = briefing ?: return
    // GPT-Live can issue a progress sentence before the consult terminal result. Both gates are
    // required so that sentence cannot begin the sequence.
    if (!flow.responseComplete || !flow.finalSegmentSeen || flow.deadlineMs != null) return
    if (flow.speechDrained) {
      flow.deadlineMs = nowMs() + WEATHER_TTL_MS
      if (hasFreshFacts(BriefingStep.Weather)) {
        showBriefingTile(flow, BriefingStep.Weather, sticky = false)
      } else {
        diagnostic("briefing-skip kind=${BriefingStep.Weather.type}")
      }
      diagnostic("briefing-drain ttlMs=$WEATHER_TTL_MS")
    } else if (hasFreshFacts(BriefingStep.Weather)) {
      if (showBriefingTile(flow, BriefingStep.Weather, sticky = true)) {
        diagnostic("briefing-show kind=${BriefingStep.Weather.type}")
      }
    } else {
      diagnostic("briefing-skip kind=${BriefingStep.Weather.type}")
    }
  }

  private fun showBriefingStepIfFresh(flow: MorningBriefing, step: BriefingStep, atMs: Long) {
    if (!hasFreshFacts(step)) {
      diagnostic("briefing-skip kind=${step.type}")
      return
    }
    if (showBriefingTile(flow, step, sticky = false, atMs = atMs)) diagnostic("briefing-show kind=${step.type}")
  }

  private fun publishIfReady() {
    val next = pending ?: return
    if (!next.responseComplete || !next.speechDrained) return
    pending = null
    val now = nowMs()
    repository.expireIfDue(now)
    val current = repository.presentation.value.tile
    if (current != null && !current.id.startsWith(VOICE_ID_PREFIX)) {
      diagnostic("suppressed-explicit kind=${next.kind}")
      return
    }
    repository.show(next.id, next.kind, JsonObject(emptyMap()), next.ttlMs, sticky = false, nowMs = now)
    diagnostic("publish kind=${next.kind} ttlMs=${next.ttlMs}")
  }

  private fun showBriefingTile(
    flow: MorningBriefing,
    step: BriefingStep,
    sticky: Boolean,
    atMs: Long = nowMs(),
  ): Boolean {
    val now = atMs
    repository.expireIfDue(now)
    val current = repository.presentation.value.tile
    if (current != null && !current.id.startsWith(briefingPrefix(flow)) && !current.id.startsWith(VOICE_ID_PREFIX)) {
      diagnostic("briefing-suppressed-explicit kind=${step.type}")
      briefing = null
      return false
    }
    repository.show(
      id = "${briefingPrefix(flow)}${step.name.lowercase()}",
      type = step.type,
      data = JsonObject(emptyMap()),
      ttlMs = if (sticky) null else step.ttlMs,
      sticky = sticky,
      nowMs = now,
    )
    return true
  }

  private fun hasFreshFacts(step: BriefingStep): Boolean = briefingFacts().let { facts ->
    facts.connected && when (step) {
      BriefingStep.Weather -> facts.weatherLoaded
      BriefingStep.Tasks -> facts.calendarLoaded
      BriefingStep.Music -> facts.mediaLoaded && facts.hasPlayingKnownMusic
    }
  }

  private fun cancelMorningBriefing(clearVisible: Boolean) {
    val flow = briefing ?: return
    briefing = null
    if (clearVisible && repository.presentation.value.tile?.id?.startsWith(briefingPrefix(flow)) == true) repository.clear(nowMs())
    diagnostic("briefing-cancel")
  }

  private fun briefingPrefix(flow: MorningBriefing): String = "briefing-${flow.id}-"

  private fun parseIntent(raw: String): Pair<HorizonTileType, Long>? {
    val text = raw
    if (text.isEmpty() || text.contains(Regex("\\b(nicht|kein|keine|ohne|statt)\\b")) ||
      text.contains("\"") || text.contains("'") || text.contains("`") ||
      text.contains(" und ") || text.contains(" dann ") || text.contains(";")) return null
    return when (text) {
      in WEATHER_QUESTIONS, in WEATHER_DISPLAY_REQUESTS -> HorizonTileType.Weather to WEATHER_TTL_MS
      in TASK_QUESTIONS, in TASK_DISPLAY_REQUESTS -> HorizonTileType.Tasks to TASKS_TTL_MS
      in HOME_QUESTIONS, in HOME_DISPLAY_REQUESTS -> HorizonTileType.Home to HOME_TTL_MS
      "was laeuft gerade", "welche musik laeuft gerade", "welches lied laeuft gerade",
      in MUSIC_DISPLAY_REQUESTS -> HorizonTileType.Music to MUSIC_TTL_MS
      else -> null
    }
  }

  private fun normalize(raw: String): String {
    var text = raw.trim().lowercase().replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
    text = text.replace(Regex("^[,\\s]*(?:hey\\s+)?jarvis[,\\s]+"), "")
    text = text.trim().replace(Regex("[.?!]+$"), "").trim()
    text = text.removePrefix("bitte ").removeSuffix(" bitte").trim()
    return text.replace(Regex("\\s+"), " ")
  }

  private companion object {
    const val VOICE_ID_PREFIX = "voice-"
    const val WEATHER_TTL_MS = 9_000L
    const val TASKS_TTL_MS = 14_000L
    const val HOME_TTL_MS = 9_000L
    const val MUSIC_TTL_MS = 8_000L
    var nextId = 1L
    var nextBriefingId = 1L
    val MORNING_BRIEFINGS = setOf("guten morgen", "guten morgen jarvis", "guten morgen, jarvis", "morgenbriefing", "morgen briefing")
    val WEATHER_QUESTIONS = setOf(
      "wie wird das wetter", "wie wird das wetter heute", "wie wird das wetter morgen", "wie wird das wetter hier", "wie wird das wetter bei uns", "wie wird das wetter zuhause", "wie wird das wetter zu hause",
      "wie wird das wetter heute hier", "wie wird das wetter heute bei uns", "wie wird das wetter heute zuhause", "wie wird das wetter heute zu hause", "wie wird das wetter heute hier bei uns",
      "wie ist das wetter", "wie ist das wetter heute", "wie sieht das wetter aus", "wie sieht das wetter heute aus", "regnet es heute", "wird es heute regnen",
    )
    val WEATHER_DISPLAY_REQUESTS = setOf(
      "zeig mir das wetter", "zeige mir das wetter", "zeig mir bitte das wetter", "zeige mir bitte das wetter",
      "wetteruebersicht", "wetteruebersicht hier", "wetteruebersicht bei uns",
      "zeig mir die wetteruebersicht", "zeige mir die wetteruebersicht",
      "wettervorhersage", "wettervorhersage hier", "wettervorhersage bei uns",
      "zeig mir die wettervorhersage", "zeige mir die wettervorhersage",
    )
    val TASK_QUESTIONS = setOf("was steht heute an", "was habe ich heute vor", "welche termine habe ich heute", "was steht bei mir heute an", "wie sieht mein tag heute aus")
    val TASK_DISPLAY_REQUESTS = setOf("zeig mir meine termine", "zeige mir meine termine", "zeig mir den kalender", "zeige mir den kalender")
    val HOME_QUESTIONS = setOf("wie ist der status zuhause", "wie ist der status zu hause", "wie ist es zuhause", "wie ist es zu hause", "wie sieht es zuhause aus", "wie sieht es zu hause aus")
    val HOME_DISPLAY_REQUESTS = setOf("zeig mir den status zuhause", "zeige mir den status zuhause", "zeig mir den status zu hause", "zeige mir den status zu hause")
    val MUSIC_DISPLAY_REQUESTS = setOf("zeig mir die musik", "zeige mir die musik", "zeig mir was laeuft", "zeige mir was laeuft")
  }
}

/** Live dashboard eligibility only; no text, timestamps, or media choice crosses this boundary. */
internal data class HorizonMorningBriefingFacts(
  val connected: Boolean = false,
  val weatherLoaded: Boolean = false,
  val calendarLoaded: Boolean = false,
  val mediaLoaded: Boolean = false,
  val hasPlayingKnownMusic: Boolean = false,
)

package ai.openclaw.app.ui

import ai.openclaw.app.voice.VoiceConversationEntry

/** The compact live transcript keeps enough recent context to make each spoken exchange readable. */
internal fun jarvisConversationForDisplay(entries: List<VoiceConversationEntry>): List<VoiceConversationEntry> =
  entries.filter { it.text.isNotBlank() }.takeLast(MAX_VISIBLE_JARVIS_CONVERSATION_ENTRIES)

/** A reader controls following only with a real drag; a new spoken turn always takes priority. */
internal fun jarvisConversationFollowsAfterDrag(
  activeEntryIdBeforeDrag: String?,
  activeEntryIdAfterDrag: String?,
  endedAtNewest: Boolean,
): Boolean = activeEntryIdBeforeDrag != activeEntryIdAfterDrag || endedAtNewest

internal const val MAX_VISIBLE_JARVIS_CONVERSATION_ENTRIES = 4

package com.fujikon.app.data

/**
 * Immutable editor state stored in one history step.
 *
 * The photo id identifies which photo an (optional, per-photo) AI result
 * belongs to. It is NOT a navigation target: the history is global (one grade
 * shared by all photos for batch export), so undo / redo / jumpTo restore the
 * grade on every photo but keep the user on the photo they are currently
 * editing. Switching photos records no step. See EditorViewModel.jumpHistory.
 */
data class EditSnapshot(
    val photoId: Long,
    val presetId: String?,
    val toneCurve: ToneCurve?,
    val adjustments: EditAdjustments,
    /** Null means "use the selected preset's own per-channel curves". */
    val channelCurves: ChannelToneCurves? = null,
    /**
     * Optional Agnes result attached to this step. Keeping the AI image inside
     * the snapshot (instead of replacing the whole editor state) means undo /
     * redo / jumpTo restore both the local grade AND the AI layer together —
     * the AI edit becomes a normal, reversible editing step.
     */
    val aiResultUri: String? = null,
    /** 0..1 opacity of the AI layer over the locally rendered frame. */
    val aiResultOpacity: Float = 1f,
    /** False hides the AI layer without deleting its cached bytes. */
    val aiResultVisible: Boolean = true
) {
    val hasAiResult: Boolean get() = aiResultUri != null && aiResultVisible && aiResultOpacity > 0.001f

    fun withAiResult(uri: String?, opacity: Float = 1f, visible: Boolean = true): EditSnapshot = copy(
        aiResultUri = uri,
        aiResultOpacity = opacity.coerceIn(0f, 1f),
        aiResultVisible = visible
    )

    fun withoutAiResult(): EditSnapshot = copy(
        aiResultUri = null,
        aiResultOpacity = 1f,
        aiResultVisible = true
    )
}

/**
 * Lightroom-style undo / redo stack for the preview editor.
 *
 * A new edit truncates any redo branch. Consecutive changes made with the same
 * [Entry.coalesceKey] inside [coalesceWindowMs] update the newest entry instead
 * of flooding the history panel while a slider is being dragged.
 */
class EditHistory(
    private val maxEntries: Int = 60,
    private val coalesceWindowMs: Long = 2_000L
) {

    data class Entry(
        val id: Long,
        val label: String,
        val snapshot: EditSnapshot,
        val createdAt: Long,
        val coalesceKey: String? = null
    )

    private val items = mutableListOf<Entry>()
    private var nextId = 1L

    val entries: List<Entry> get() = items
    var index: Int = -1
        private set

    val current: Entry? get() = items.getOrNull(index)
    val canUndo: Boolean get() = index > 0
    val canRedo: Boolean get() = index >= 0 && index < items.lastIndex

    /** The AI layer that belongs to the currently selected history step. */
    val currentAiResult: String?
        get() = current?.snapshot?.takeIf { it.hasAiResult }?.aiResultUri

    /** Attach / replace / clear the AI layer as a new, undoable history step. */
    fun recordAiResult(label: String, snapshot: EditSnapshot, now: Long = System.currentTimeMillis()): Entry =
        record(label, snapshot, coalesceKey = "ai-result", now = now)

    fun reset(snapshot: EditSnapshot, label: String, now: Long = System.currentTimeMillis()) {
        items.clear()
        index = -1
        items += Entry(nextId++, label, snapshot, now)
        index = 0
    }

    /**
     * Appends a step, or replaces the newest step when it is a continuation of
     * the same interaction. Returns the entry that now represents the state.
     */
    fun record(
        label: String,
        snapshot: EditSnapshot,
        coalesceKey: String? = null,
        now: Long = System.currentTimeMillis()
    ): Entry {
        if (index < items.lastIndex) {
            items.subList(index + 1, items.size).clear()
        }

        val last = items.lastOrNull()
        if (last != null &&
            coalesceKey != null &&
            last.coalesceKey == coalesceKey &&
            now - last.createdAt <= coalesceWindowMs
        ) {
            val updated = last.copy(label = label, snapshot = snapshot, createdAt = now)
            items[items.lastIndex] = updated
            index = items.lastIndex
            return updated
        }

        val entry = Entry(nextId++, label, snapshot, now, coalesceKey)
        items += entry
        while (items.size > maxEntries) {
            items.removeAt(0)
        }
        index = items.lastIndex
        return entry
    }

    fun undo(): Entry? {
        if (!canUndo) return null
        index -= 1
        return items[index]
    }

    fun redo(): Entry? {
        if (!canRedo) return null
        index += 1
        return items[index]
    }

    fun jumpTo(target: Int): Entry? {
        if (target !in items.indices) return null
        index = target
        return items[target]
    }
}

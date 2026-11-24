// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

/** How a register's text is put back: as characters, as whole lines (the text has no final line break here), or as a rectangle of [width] columns. */
internal enum class RegType { CHAR, LINE, BLOCK }

/** The content of a register. For [RegType.LINE] and [RegType.BLOCK] the lines are joined by "\n" without a final one. */
internal class Reg(val text: String, val type: RegType, val width: Int = 0)

/** What a Visual command was made with, to repeat it on the same amount of text. */
internal class VisualInfo(val mode: VimMode, val lines: Int, val width: Int)

/** What a write into the memory did: kept, refused because nothing may be kept in this field, or refused because the register would be too big. */
internal enum class Kept { OK, OFF, FULL }

/** How a recording ended: stored in its register, dropped (it was broken, too long, or capture is not allowed), or the register would be too big. */
internal enum class Recorded { STORED, DROPPED, FULL }

/** The last substitute: pattern, replacement and flags, for `:&&` and `:s` without arguments. */
internal class SubState(val pattern: String, val replacement: String, val flags: String)

/** What "." plays: the register and count it was given, the keys of the change (and the text typed after it), and the Visual area it worked on. */
internal class DotPlay(val reg: Char?, val count: Int?, val keys: List<Int>, val visual: VisualInfo?)

internal sealed interface DotRepeat {
    data object None : DotRepeat
    data object Broken : DotRepeat
    class Ready(val play: DotPlay) : DotRepeat
}

/**
 * Everything the Vim layer remembers between commands, and the ONLY place that keeps it: the registers (the macros are registers), the "."
 * record, a recording in progress, the marks, the last search, the last substitute, the last find, the histories of the command line and of
 * searches, and the last inserted text. All of it is in memory, never written anywhere.
 *
 * One rule, owned here: the clipboard capture decision ([VimRules.registerAllowed]) is asked again at the moment of EVERY write, through
 * [mayKeep]. A write that is refused keeps nothing, and a record that is being built when the decision turns to "no" (a recording, the
 * typed text of a change that "." would repeat) is dropped, so nothing typed after the switch is kept. The stores are private classes of
 * this file and every method that changes one checks the decision first; no other class of the package keeps field text or typed text
 * across commands (VimStoresTest lists the fields of the package). [clear] forgets all of it (screen lock, wipe, the setting turned off).
 */
internal class VimMemory(private val rules: VimRules) {
    private val regs = RegisterStore()
    private val marks = MarkStore()
    private var dot: DotRecord? = null
    /** The "." record is the change whose typed text is still to come (an insert command that has not ended yet). */
    private var dotOpen = false
    private var recName: Char? = null
    private val recBuf = StringBuilder()
    private var recKeys = 0
    private var recBroken = false
    private var recDropped = false
    private var macro: Char? = null
    private var find: Pair<Char, Int>? = null
    private var searchFwd = true
    private var searchOff: VimSearch.Offset = VimSearch.Offset.NONE
    private var sub: SubState? = null
    private val cmdHistory = ArrayList<String>()
    private val searchHistory = ArrayList<String>()

    /** True where the clipboard capture rule lets the layer keep something. Asked at every write; the answer is never kept. */
    fun mayKeep(): Boolean = rules.registerAllowed()

    /** Forgets everything. */
    fun clear() {
        regs.clear()
        marks.clear()
        dot = null
        dotOpen = false
        dropRecording()
        recDropped = false
        macro = null
        find = null
        searchFwd = true
        searchOff = VimSearch.Offset.NONE
        sub = null
        cmdHistory.clear()
        searchHistory.clear()
    }

    /** The names of the stores that hold something (the tests; one label per store). */
    fun nonEmpty(): List<String> {
        val out = ArrayList<String>()
        if (regs.names().isNotEmpty()) out.add("registers")
        if (regs.lastInsert != null) out.add("lastInsert")
        if (regs.lastSearch != null) out.add("lastSearch")
        if (regs.lastCommand != null) out.add("lastCommand")
        if (!marks.isEmpty() || marks.base != null) out.add("marks")
        if (dot != null) out.add("dot")
        if (recName != null || recBuf.isNotEmpty() || recKeys != 0) out.add("recording")
        if (macro != null) out.add("lastMacro")
        if (find != null) out.add("lastFind")
        if (searchFwd.not() || searchOff != VimSearch.Offset.NONE) out.add("searchState")
        if (sub != null) out.add("substitute")
        if (cmdHistory.isNotEmpty()) out.add("commandHistory")
        if (searchHistory.isNotEmpty()) out.add("searchHistory")
        return out
    }

    // ---- registers --------------------------------------------------------------------------------------------------------------------

    /** The content of register [name] for a put or a play; null when it is empty or the name is not a register of the layer. */
    fun get(name: Char): Reg? = regs.get(name)

    /** The unnamed register: what was yanked or deleted last. */
    val unnamed: Reg? get() = regs.unnamed

    /** The registers that hold something (the unnamed one, 0, 1 to 9, "-", a to z). */
    fun names(): List<Char> = regs.names()

    val lastInsert: String? get() = regs.lastInsert
    val lastSearch: String? get() = regs.lastSearch
    val lastCommand: String? get() = regs.lastCommand

    /** True for the names that a yank or delete can write (not "." "/" ":"). */
    fun writable(name: Char) = regs.writable(name)

    /** A yank into [name] (null: the unnamed register, which also fills register 0). */
    fun yank(name: Char?, reg: Reg): Kept {
        if (name == '_') return Kept.OK
        if (!mayKeep()) return Kept.OFF
        return if (regs.yank(name, reg)) Kept.OK else Kept.FULL
    }

    /**
     * A delete or change into [name]. Where nothing may be kept the text is not stored and the registers it would have replaced are emptied, so
     * that no stale text is put later. [multiLine]: whole lines or over a line break; [useRegOne]: a motion that always uses register 1.
     */
    fun delete(name: Char?, reg: Reg, multiLine: Boolean, useRegOne: Boolean): Kept {
        if (name == '_') return Kept.OK
        if (!mayKeep()) { regs.invalidateDelete(name, multiLine); return Kept.OK }
        return if (regs.delete(name, reg, multiLine, useRegOne)) Kept.OK else Kept.FULL
    }

    /** A recorded macro, or text given to a register by a test: an uppercase [name] appends; an empty recording empties the register. */
    fun store(name: Char, reg: Reg): Kept {
        if (!mayKeep()) return Kept.OFF
        return if (regs.store(name, reg)) Kept.OK else Kept.FULL
    }

    /** The unnamed register points at [reg] only (the "+ and "* yank, whose text is the system clipboard's). */
    fun point(reg: Reg) { if (mayKeep()) regs.point(reg) }

    /** The last inserted text (the "." register). */
    fun setLastInsert(text: String) { if (mayKeep() && text.length <= MAX_CHARS) regs.lastInsert = text }

    fun setLastCommand(text: String) { if (mayKeep()) regs.lastCommand = text }

    // ---- search, substitute, find, histories ---------------------------------------------------------------------------------------------

    val searchForward: Boolean get() = searchFwd
    val searchOffset: VimSearch.Offset get() = searchOff

    /** The pattern, direction and offset of a search typed on the command line (also the "/" register). */
    fun setSearch(pattern: String, forward: Boolean, offset: VimSearch.Offset) {
        if (!mayKeep()) return
        regs.lastSearch = pattern
        searchFwd = forward
        searchOff = offset
    }

    /** A pattern that an ex command used: it is the last search, and an offset that belonged to an earlier search is gone. */
    fun setSearchPattern(pattern: String) {
        if (!mayKeep()) return
        regs.lastSearch = pattern
        searchOff = VimSearch.Offset.NONE
    }

    val substitute: SubState? get() = sub

    fun setSubstitute(pattern: String, replacement: String, flags: String) { if (mayKeep()) sub = SubState(pattern, replacement, flags) }

    val lastFind: Pair<Char, Int>? get() = find

    fun setLastFind(type: Char, ch: Int) { if (mayKeep()) find = type to ch }

    val lastMacro: Char? get() = macro

    fun setLastMacro(name: Char) { if (mayKeep()) macro = name }

    /** The history of the command line (":") or of searches, oldest first. */
    fun history(commandLine: Boolean): List<String> = if (commandLine) cmdHistory else searchHistory

    fun addHistory(commandLine: Boolean, line: String) {
        if (!mayKeep() || line.isEmpty()) return
        val list = if (commandLine) cmdHistory else searchHistory
        list.remove(line)
        list.add(line)
        while (list.size > MAX_HISTORY) list.removeAt(0)
    }

    // ---- marks (of the field the layer is in) -------------------------------------------------------------------------------------------

    /** The text the marks belong to; null when there are none. */
    val marksBase: String? get() = marks.base

    fun markSet(name: Char, text: String, offset: Int) { if (mayKeep()) marks.set(name, text, offset) }

    /** The offset of mark [name] in [text], or -1 when it is not set (or the text is not the one it belongs to). */
    fun markGet(name: Char, text: String): Int = marks.get(name, text)

    fun markIsSet(name: Char) = marks.isSet(name)

    fun markNames(): List<Char> = marks.names()

    /** Forgets all marks when [text] is not the text they were set in. True when something was forgotten. */
    fun marksForgetIfChanged(text: String): Boolean = marks.forgetIfChanged(text)

    fun marksClear() = marks.clear()

    /** An edit replaced [start, end) of [old]: the marks follow it; where nothing may be kept the marks are forgotten (they would hold the new text). */
    fun marksRemapEdit(old: String, start: Int, end: Int, replacement: String) {
        if (mayKeep()) marks.remapEdit(old, start, end, replacement) else marks.clear()
    }

    /** The text changed from [old] to [new] in a way the layer cannot follow exactly: the marks follow their lines. */
    fun marksRemap(old: String, new: String) {
        if (mayKeep()) marks.remap(old, new) else marks.clear()
    }

    // ---- the "." record ---------------------------------------------------------------------------------------------------------------

    /**
     * A change is complete: it becomes the record that "." repeats. [openForInsert]: the change goes into insert mode, and the text typed
     * there joins the record through [dotTyped]. Where nothing may be kept the old record stays as it is (like a register that can still be put).
     */
    fun dotSet(reg: Char?, count: Int?, keys: List<Int>, visual: VisualInfo?, openForInsert: Boolean) {
        if (!mayKeep()) { dotOpen = false; return }
        dot = DotRecord(reg, count, ArrayList(keys), visual)
        dotOpen = openForInsert
    }

    /** The insert of the open change ended with [typed]: it joins the record, followed by Esc. Where nothing may be kept now the record is dropped whole. */
    fun dotTyped(typed: String) {
        val d = dot ?: return
        if (!dotOpen) return
        dotOpen = false
        if (!mayKeep()) { dot = null; return }
        for (ch in typed) d.keys.add(ch.code)
        d.keys.add(ESC_KEY)
    }

    /** What was typed in the open change cannot be told: "." cannot repeat it. */
    fun dotBroken() {
        if (!dotOpen) return
        dotOpen = false
        dot?.broken = true
    }

    /** The insert that was open ended without being told to the record (a play, Ctrl-O, a reset). */
    fun dotCloseInsert() { dotOpen = false }

    fun dotRepeat(newCount: Int?): DotRepeat {
        val d = dot ?: return DotRepeat.None
        if (d.broken) return DotRepeat.Broken
        val keep = mayKeep()
        val count = newCount ?: d.count
        if (newCount != null && keep) d.count = newCount
        val r = d.reg
        val use = if (r != null && r in '1'..'8') r + 1 else r // "1p . puts "2p, and so on
        if (use != r && keep) d.reg = use
        return DotRepeat.Ready(DotPlay(use, count, d.keys.toList(), d.visual))
    }

    // ---- the recording of a macro -------------------------------------------------------------------------------------------------------

    /** The register a macro is being recorded into, or null. */
    val recording: Char? get() = recName

    /** Starts recording into [name]; false where nothing may be kept. */
    fun recordStart(name: Char): Boolean {
        if (!mayKeep()) return false
        recName = name
        recBuf.setLength(0)
        recKeys = 0
        recBroken = false
        return true
    }

    /** One more key of the macro. False when the recording was dropped (nothing may be kept now, it was broken, or it is too long). */
    fun recordKey(code: Int): Boolean {
        if (recName == null) return true
        if (!mayKeep() || recBroken || recKeys + 1 > MAX_MACRO_KEYS) { dropRecording(); return false }
        recBuf.appendCodePoint(if (code == 10) 13 else code) // Vim keeps Enter as a carriage return
        recKeys++
        return true
    }

    /** The text typed in an insert of the recording. Dropped with the recording when nothing may be kept now; a recording that gets too long is broken. */
    fun recordTyped(typed: String) {
        if (recName == null) return
        if (!mayKeep()) { dropRecording(); recDropped = true; return }
        if (recKeys + typed.length + 1 > MAX_MACRO_KEYS) recBroken = true
        else { recBuf.append(typed.replace('\n', '\r')); recKeys += typed.length }
    }

    /** What was typed in an insert of the recording cannot be told: the recording cannot be kept. */
    fun recordBroken() { if (recName != null) recBroken = true }

    /** Ends the recording: the macro goes into its register. Null when nothing was being recorded. */
    fun recordStop(): Recorded? {
        val name = recName ?: return null
        val text = recBuf.toString()
        val broken = recBroken
        dropRecording()
        if (broken || !mayKeep()) return Recorded.DROPPED
        return if (regs.store(name, Reg(text, RegType.CHAR))) Recorded.STORED else Recorded.FULL
    }

    private fun dropRecording() {
        recName = null
        recBuf.setLength(0)
        recKeys = 0
        recBroken = false
    }

    /** Called at the start of every key: a recording stops the moment the decision turns to "no", and the next answer says so. */
    fun sync() {
        if (recName != null && !mayKeep()) { dropRecording(); recDropped = true }
    }

    /** True once after a recording was dropped by the decision turning to "no" (so the user is told). */
    fun takeDropped(): Boolean {
        val d = recDropped
        recDropped = false
        return d
    }

    private class DotRecord(var reg: Char?, var count: Int?, val keys: ArrayList<Int>, val visual: VisualInfo?) {
        var broken = false
    }

    companion object {
        /** A register holds at most this many characters. */
        const val MAX_CHARS = 20_000
        /** A macro holds at most this many keys. */
        const val MAX_MACRO_KEYS = 2_000
        const val MAX_HISTORY = 50
        private const val ESC_KEY = 27
    }
}

/**
 * The registers: the unnamed one, 0 (yank), 1 to 9 (the delete ring), "-" (small delete), a to z (A to Z append), and the read-only ".", "/" and ":"
 * (last inserted text, last search pattern, last command line). The system clipboard (+ and *) is not here: it is read and written through the
 * keyboard's own copy and paste owners. This class only stores; whether anything may be kept is decided by [VimMemory], its only user. A register
 * holds at most [VimMemory.MAX_CHARS]. The rules for what goes where are Vim's (op_yank, op_delete, shift_delete_registers); the golden table
 * compares them with the real vim.
 */
private class RegisterStore {
    private val named = HashMap<Char, Reg>()
    private var yank0: Reg? = null
    private val ring = arrayOfNulls<Reg>(9)
    private var small: Reg? = null
    private var unnamedValue: Reg? = null // what the unnamed register points to (it follows the register that was written last)

    var lastInsert: String? = null
    var lastSearch: String? = null
    var lastCommand: String? = null

    val unnamed: Reg? get() = unnamedValue

    fun clear() {
        named.clear()
        yank0 = null
        for (i in ring.indices) ring[i] = null
        small = null
        unnamedValue = null
        lastInsert = null
        lastSearch = null
        lastCommand = null
    }

    fun get(name: Char): Reg? = when (name) {
        '"' -> unnamedValue
        '0' -> yank0
        in '1'..'9' -> ring[name - '1']
        '-' -> small
        in 'a'..'z' -> named[name]
        in 'A'..'Z' -> named[name.lowercaseChar()]
        '.' -> lastInsert?.let { Reg(it, RegType.CHAR) }
        '/' -> lastSearch?.let { Reg(it, RegType.CHAR) }
        ':' -> lastCommand?.let { Reg(it, RegType.CHAR) }
        else -> null
    }

    fun writable(name: Char) = name in 'a'..'z' || name in 'A'..'Z' || name == '"' || name in '0'..'9' || name == '-'

    fun names(): List<Char> {
        val out = ArrayList<Char>()
        if (unnamedValue != null) out.add('"')
        if (yank0 != null) out.add('0')
        for (i in ring.indices) if (ring[i] != null) out.add('1' + i)
        if (small != null) out.add('-')
        for (c in 'a'..'z') if (named[c] != null) out.add(c)
        return out
    }

    private fun joined(old: Reg?, add: Reg): Reg? {
        if (old == null) return add
        val lineMode = old.type == RegType.LINE || add.type == RegType.LINE
        val text = if (lineMode) old.text + "\n" + add.text else old.text + add.text
        if (text.length > VimMemory.MAX_CHARS) return null
        return Reg(text, if (lineMode) RegType.LINE else old.type, maxOf(old.width, add.width))
    }

    fun yank(name: Char?, reg: Reg): Boolean {
        if (reg.text.length > VimMemory.MAX_CHARS) return false
        when {
            name == '_' -> return true
            name == null || name == '"' -> { yank0 = reg; unnamedValue = reg }
            name in 'A'..'Z' -> {
                val key = name.lowercaseChar()
                val merged = joined(named[key], reg) ?: return false
                named[key] = merged
                unnamedValue = merged
            }
            name in 'a'..'z' -> { named[name] = reg; unnamedValue = reg }
            name in '0'..'9' || name == '-' -> { setNumbered(name, reg); unnamedValue = reg }
        }
        return true
    }

    private fun setNumbered(name: Char, reg: Reg) {
        when (name) {
            '0' -> yank0 = reg
            '-' -> small = reg
            in '1'..'9' -> ring[name - '1'] = reg
            else -> {} // not a register that a delete can write
        }
    }

    fun delete(name: Char?, reg: Reg, multiLine: Boolean, useRegOne: Boolean): Boolean {
        if (reg.text.length > VimMemory.MAX_CHARS) return false
        if (name == '_') return true
        var appended: Reg? = null
        if (name != null && name != '"') {
            when (name) {
                in 'a'..'z' -> { named[name] = reg; appended = reg }
                in 'A'..'Z' -> {
                    val key = name.lowercaseChar()
                    val merged = joined(named[key], reg) ?: return false
                    named[key] = merged
                    appended = merged
                }
                else -> { setNumbered(name, reg); appended = reg }
            }
        }
        var point = appended
        if (reg.type == RegType.LINE || multiLine || useRegOne) {
            for (i in 8 downTo 1) ring[i] = ring[i - 1]
            ring[0] = reg
            if (appended == null || name in 'a'..'z') point = reg // "A (append) leaves the pointer on the appended register
            if (name in 'A'..'Z') point = appended
        }
        if ((name == null || name == '"') && reg.type != RegType.LINE && !multiLine) {
            small = reg
            point = reg
        }
        unnamedValue = point ?: reg
        return true
    }

    fun invalidateDelete(name: Char?, multiLine: Boolean) {
        if (name == '_') return
        unnamedValue = null
        when (name) {
            in 'a'..'z' -> named.remove(name)
            in 'A'..'Z' -> if (name != null) named.remove(name.lowercaseChar())
            '0' -> yank0 = null
            '-' -> small = null
            in '1'..'9' -> if (name != null) ring[name - '1'] = null
            else -> {}
        }
        if (multiLine) for (i in ring.indices) ring[i] = null else small = null
    }

    fun store(name: Char, reg: Reg): Boolean {
        if (reg.text.length > VimMemory.MAX_CHARS) return false
        when (name) {
            in 'a'..'z' -> if (reg.text.isEmpty()) named.remove(name) else named[name] = reg
            in 'A'..'Z' -> if (reg.text.isNotEmpty()) {
                val key = name.lowercaseChar()
                named[key] = joined(named[key], reg) ?: return false
            }
            '0' -> yank0 = reg
            '-' -> small = reg
            in '1'..'9' -> ring[name - '1'] = reg
            else -> {}
        }
        return true
    }

    fun point(reg: Reg) { unnamedValue = reg }
}

/**
 * The marks of one field (a to z, the visual marks < and >, the change marks [ and ], the last insert ^, and the context mark ' that
 * `` and '' jump to), as (line, column) like Vim's. A mark is valid for the text it was set in ([base]): an edit made through the layer
 * moves the marks of the lines after it ([remapEdit], [remap]); when the text is not the one the marks were set in (it was changed by the
 * keyboard or the app), every mark is forgotten ([forgetIfChanged]). The one text kept is [base]. Only [VimMemory] uses this class.
 */
private class MarkStore {
    private val marks = HashMap<Char, IntArray>()

    var base: String? = null
        private set

    fun isEmpty() = marks.isEmpty()

    fun clear() {
        marks.clear()
        base = null
    }

    fun forgetIfChanged(text: String): Boolean {
        val b = base ?: return false
        if (b == text) return false
        clear()
        return true
    }

    fun set(name: Char, text: String, offset: Int) {
        if (base != null && base != text) clear()
        base = text
        val line = VimLines.lineOf(text, offset)
        marks[name] = intArrayOf(line, offset - VimLines.lineStartOf(text, line))
    }

    fun get(name: Char, text: String): Int {
        val m = marks[name] ?: return -1
        if (base != text) return -1
        if (m[0] >= VimLines.lineCount(text)) return -1
        val ls = VimLines.lineStartOf(text, m[0])
        val le = text.indexOf('\n', ls).let { if (it < 0) text.length else it }
        return minOf(ls + m[1], le)
    }

    fun isSet(name: Char) = marks.containsKey(name)

    fun names(): List<Char> = marks.keys.sorted()

    fun remap(old: String, new: String) {
        if (marks.isEmpty()) { base = null; return }
        if (base != old) { clear(); return }
        if (old == new) return
        val map = VimLines.lineMap(old.split('\n'), new.split('\n'))
        val it = marks.entries.iterator()
        val moved = HashMap<Char, IntArray>()
        while (it.hasNext()) {
            val e = it.next()
            val target = if (e.value[0] < map.size) map[e.value[0]] else -1
            if (target >= 0) moved[e.key] = intArrayOf(target, e.value[1])
        }
        marks.clear()
        marks.putAll(moved)
        base = new
    }

    /**
     * The edit that replaced [start, end) of [old] with [replacement] is known, so the marks follow it exactly: a line wholly before it stays, a
     * line that starts at or after its end moves by the lines the edit added or removed, a line wholly inside it is gone (its marks with it), and
     * a line it only touches stays (a line it joins into the first one goes into that one).
     */
    fun remapEdit(old: String, start: Int, end: Int, replacement: String) {
        if (marks.isEmpty()) { base = null; return }
        if (base != old) { clear(); return }
        val removed = old.substring(start, end)
        val addedNl = replacement.count { it == '\n' }
        val removedNl = removed.count { it == '\n' }
        val delta = addedNl - removedNl
        val firstLine = VimLines.lineOf(old, start)
        val moved = HashMap<Char, IntArray>()
        for ((name, m) in marks) {
            val ls = VimLines.lineStartOf(old, m[0])
            val le = old.indexOf('\n', ls).let { if (it < 0) old.length else it }
            val covered = (start <= ls && end > le) || (le == old.length && start < ls && end >= le) || (le == old.length && ls == 0 && start == 0 && end >= le)
            val target = when {
                le < start -> m[0]
                ls >= end -> m[0] + delta
                addedNl >= removedNl -> m[0] // no line is lost: the lines are changed in place
                covered -> -1
                m[0] == firstLine -> m[0]
                else -> firstLine
            }
            if (target >= 0) moved[name] = intArrayOf(target, m[1])
        }
        marks.clear()
        marks.putAll(moved)
        base = old.substring(0, start) + replacement + old.substring(end)
    }
}

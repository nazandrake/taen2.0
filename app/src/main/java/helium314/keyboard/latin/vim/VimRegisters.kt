// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

/** How a register's text is put back: as characters, as whole lines (the text has no final line break here), or as a rectangle of [width] columns. */
internal enum class RegType { CHAR, LINE, BLOCK }

/** The content of a register. For [RegType.LINE] and [RegType.BLOCK] the lines are joined by "\n" without a final one. */
internal class Reg(val text: String, val type: RegType, val width: Int = 0)

/**
 * The registers of the Vim layer, in memory only: the unnamed one, 0 (yank), 1 to 9 (the delete ring), "-" (small delete), a to z (A to Z
 * append), and the read-only ".", "/" and ":" (last inserted text, last search pattern, last command line). The system clipboard (+ and *)
 * is not here: it is read and written through the keyboard's own copy and paste owners. Whether anything may be kept is decided by the
 * caller (the clipboard capture rule, one owner: [VimRules.registerAllowed]); this class only stores, and a register holds at most [MAX_CHARS].
 * The rules for what goes where are Vim's (op_yank, op_delete, shift_delete_registers); the golden table compares them with the real vim.
 */
internal class VimRegisters {
    private val named = HashMap<Char, Reg>()
    private var yank0: Reg? = null
    private val ring = arrayOfNulls<Reg>(9)
    private var small: Reg? = null
    private var unnamedKey: Char = '"'
    private var unnamedValue: Reg? = null // what the unnamed register points to (it follows the register that was written last)

    var lastInsert: String? = null
    var lastSearch: String? = null
    var lastCommand: String? = null

    /** The unnamed register: what was yanked or deleted last. */
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

    /** The content of register [name] for a put or a play; null when it is empty or the name is not a register of this class. */
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

    /** True for the names that can be written by a yank or delete (not "." "/" ":"). */
    fun writable(name: Char) = name in 'a'..'z' || name in 'A'..'Z' || name == '"' || name in '0'..'9' || name == '-'

    /** The register that [name] stands for when it is written; the tests list the ones that a command filled. */
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
        if (text.length > MAX_CHARS) return null
        return Reg(text, if (lineMode) RegType.LINE else old.type, maxOf(old.width, add.width))
    }

    /** A yank into [name] (null or '"': the unnamed register, which also fills register 0). False when an append would exceed the limit. */
    fun yank(name: Char?, reg: Reg): Boolean {
        if (reg.text.length > MAX_CHARS) return false
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
            else -> ring[name - '1'] = reg
        }
    }

    /**
     * A delete or change into [name]. [multiLine] is a delete of whole lines or over a line break; [useRegOne] is one of the motions that
     * always use register 1 (% ( ) ` / ? n N { }). Without a name: register 1 and the ring shift for those, else the small delete register.
     */
    fun delete(name: Char?, reg: Reg, multiLine: Boolean, useRegOne: Boolean): Boolean {
        if (reg.text.length > MAX_CHARS) return false
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

    /**
     * A delete or change in a field where nothing may be kept: the text is not stored, and the registers it would have replaced are emptied
     * (the unnamed one, the named target, register 1 and the ring, or the small delete register), so that no stale text is put later.
     */
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

    /** A recorded macro into [name] (an uppercase letter appends): the unnamed register is not touched, and an empty recording empties the register. */
    fun store(name: Char, reg: Reg): Boolean {
        if (reg.text.length > MAX_CHARS) return false
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

    /** The unnamed register points at [reg] only (the "+ and "* yank, whose text is the system clipboard's). */
    fun point(reg: Reg) { unnamedValue = reg }

    companion object {
        /** A register holds at most this many characters. */
        const val MAX_CHARS = 20_000
    }
}

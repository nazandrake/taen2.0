// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.vim

/**
 * A matcher for the Vim pattern subset of the search and substitute commands (magic mode): literals, `.`, `*`, `\+`, `\=`, `\?`, `\{n,m}`,
 * `^`, `$`, `\<`, `\>`, `[classes]`, `\( \)`, `\%( \)`, `\|`, the class escapes `\s \d \w \a \l \u \x \h` and their capitals, `\c` and `\C`.
 * It is a Thompson/Pike machine with a thread list: no recursion and no backtracking, so a pattern cannot overflow the stack or take
 * exponential time; the work is bounded by [Budget] (steps and time) and the size of the program. When the budget is spent the search stops
 * and says so ([Budget.exhausted]); it never answers from a half-finished search.
 * It works on one line (no "\n" in the pattern or the text), and a character is a code point with the combining marks that follow it.
 */
internal class VimRegex private constructor(private val prog: Array<Inst>, val groups: Int, private val ignoreCase: Boolean) {
    /** Steps and time a search may use; [exhausted] is set when either is spent. */
    class Budget(private val maxSteps: Int, private val deadline: Long, private val clock: () -> Long) {
        var steps = 0
            private set
        var exhausted = false
            private set

        /** Counts [n] steps; false when the budget is spent (the caller stops). */
        fun spend(n: Int): Boolean {
            if (exhausted) return false
            steps += n
            if (steps > maxSteps || ((steps and 1023) < n && clock() > deadline)) exhausted = true
            return !exhausted
        }
    }

    /** A match: [start] and [end] are offsets in the line; [caps] holds the group offsets (start, end pairs; -1 when a group did not take part). */
    class Match(val start: Int, val end: Int, val caps: IntArray) {
        fun group(i: Int, line: String): String {
            if (2 * i + 1 >= caps.size) return ""
            val s = caps[2 * i]
            val e = caps[2 * i + 1]
            return if (s < 0 || e < s) "" else line.substring(s, e)
        }
    }

    /** The result of [compile]: a matcher, or the reason there is none. */
    sealed interface Compiled {
        class Ok(val regex: VimRegex) : Compiled
        /** The pattern uses something outside the subset or is not well formed. */
        data object Unsupported : Compiled
        /** The program would be larger than [MAX_PROGRAM], or a repeat count larger than [MAX_REPEAT]. */
        data object TooBig : Compiled
    }

    // ---- program ---------------------------------------------------------------------------------------------------------

    private class Inst(val op: Int, val a: Int = 0, val b: Int = 0, val set: CharSet? = null)

    private class CharSet(val ranges: IntArray, val negated: Boolean, val classes: Int) {
        fun matches(cp: Int, ic: Boolean): Boolean {
            var hit = inRanges(cp) || inClasses(cp)
            if (!hit && ic) {
                val lo = Character.toLowerCase(cp)
                val up = Character.toUpperCase(cp)
                hit = (lo != cp && (inRanges(lo) || inClasses(lo))) || (up != cp && (inRanges(up) || inClasses(up)))
            }
            return hit != negated
        }

        private fun inRanges(cp: Int): Boolean {
            var i = 0
            while (i < ranges.size) {
                if (cp >= ranges[i] && cp <= ranges[i + 1]) return true
                i += 2
            }
            return false
        }

        private fun inClasses(cp: Int): Boolean {
            var c = classes
            var k = 0
            while (c != 0) {
                if (c and 1 != 0 && classTest(k, cp)) return true
                c = c ushr 1
                k++
            }
            return false
        }
    }

    // the nodes of the parsed pattern
    private sealed interface Node
    private class Lit(val cp: Int) : Node
    private object AnyChar : Node
    private class Cls(val set: CharSet) : Node
    private class Seq(val items: List<Node>) : Node
    private class Alt(val branches: List<Node>) : Node
    private class Rep(val node: Node, val min: Int, val max: Int, val greedy: Boolean) : Node
    private class Group(val node: Node, val index: Int) : Node
    private class Assert(val kind: Int) : Node

    // ---- running ---------------------------------------------------------------------------------------------------------

    /** The leftmost match that starts at [from] or later (Perl-style priority among the matches at one place), or null. Check [Budget.exhausted] after null. */
    fun find(line: String, from: Int, budget: Budget): Match? {
        val n = line.length
        val ncap = 2 * (groups + 1)
        var clist = ThreadList(prog.size)
        var nlist = ThreadList(prog.size)
        var best: IntArray? = null
        var pos = from.coerceIn(0, n)
        while (true) {
            if (best == null) addThread(clist, 0, pos, line, IntArray(ncap) { -1 }.also { it[0] = pos })
            val next = if (pos < n) clusterEnd(line, pos) else n + 1
            if (clist.size == 0) { // nothing alive: a match can only start later
                if (best != null || pos >= n || !budget.spend(1)) break
                clist.clear() // the seed that died left its marks
                pos = next
                continue
            }
            val cp = if (pos < n) line.codePointAt(pos) else -1
            if (!budget.spend(clist.size + 1)) return null
            var i = 0
            cutoff@ while (i < clist.size) {
                val pc = clist.pcs[i]
                val caps = clist.caps[i]
                i++
                if (caps == null) continue
                val ins = prog[pc]
                when (ins.op) {
                    OP_CHAR -> if (cp >= 0 && same(cp, ins.a)) addThread(nlist, pc + 1, next, line, caps.copyOf())
                    OP_ANY -> if (cp >= 0) addThread(nlist, pc + 1, next, line, caps.copyOf())
                    OP_SET -> if (cp >= 0 && ins.set?.matches(cp, ignoreCase) == true) addThread(nlist, pc + 1, next, line, caps.copyOf())
                    OP_MATCH -> {
                        caps[1] = pos
                        best = caps
                        break@cutoff // the threads of lower priority are dropped
                    }
                }
            }
            val t = clist
            clist = nlist
            nlist = t
            nlist.clear()
            if (pos >= n) break
            pos = next
        }
        val m = best ?: return null
        return Match(m[0], m[1], m)
    }

    private fun same(a: Int, b: Int): Boolean = a == b || (ignoreCase && Character.toLowerCase(a) == Character.toLowerCase(b))

    private class ThreadList(size: Int) {
        val pcs = IntArray(size)
        val caps = arrayOfNulls<IntArray>(size)
        private val seen = IntArray(size)
        private var gen = 1
        var size = 0
            private set

        fun has(pc: Int) = seen[pc] == gen
        fun mark(pc: Int) { seen[pc] = gen }
        fun push(pc: Int, c: IntArray) {
            pcs[size] = pc
            caps[size] = c
            size++
        }

        fun clear() {
            gen++
            size = 0
        }
    }

    /**
     * Adds the thread at [pc] and what it reaches without consuming a character, in priority order, using an explicit stack. [at] is the
     * position where the thread will look at the next character.
     */
    private fun addThread(list: ThreadList, startPc: Int, at: Int, line: String, startCaps: IntArray) {
        val stackPc = ArrayList<Int>()
        val stackCaps = ArrayList<IntArray>()
        stackPc.add(startPc)
        stackCaps.add(startCaps)
        while (stackPc.isNotEmpty()) {
            val pc = stackPc.removeAt(stackPc.size - 1)
            var caps = stackCaps.removeAt(stackCaps.size - 1)
            if (list.has(pc)) continue
            list.mark(pc)
            val ins = prog[pc]
            when (ins.op) {
                OP_JMP -> { stackPc.add(ins.a); stackCaps.add(caps) }
                OP_SPLIT -> {
                    // the second branch is pushed first so that the first one is tried first
                    stackPc.add(ins.b); stackCaps.add(caps.copyOf())
                    stackPc.add(ins.a); stackCaps.add(caps)
                }
                OP_SAVE -> {
                    caps = caps.copyOf()
                    caps[ins.a] = at
                    stackPc.add(pc + 1); stackCaps.add(caps)
                }
                OP_ASSERT -> if (holds(ins.a, line, at)) { stackPc.add(pc + 1); stackCaps.add(caps) }
                else -> list.push(pc, caps) // a character instruction or the end of the program
            }
        }
    }

    private fun holds(kind: Int, line: String, at: Int): Boolean = when (kind) {
        A_BOL -> at == 0
        A_EOL -> at >= line.length
        A_WORD_START -> at < line.length && isWord(line.codePointAt(at)) && (at == 0 || !isWord(line.codePointBefore(at)))
        A_WORD_END -> at > 0 && isWord(line.codePointBefore(at)) && (at >= line.length || !isWord(line.codePointAt(at)))
        else -> false
    }

    /** The end of the character at [p]: the code point and the combining marks that follow it. */
    private fun clusterEnd(line: String, p: Int): Int {
        var i = p + Character.charCount(line.codePointAt(p))
        while (i < line.length) {
            val c = line.codePointAt(i)
            if ((c in 0x0300..0x036F) || c == 0xFE0F || (c in 0x20D0..0x20FF) || (c in 0x1AB0..0x1AFF)) i += Character.charCount(c) else break
        }
        return i
    }

    companion object {
        private const val OP_CHAR = 0
        private const val OP_ANY = 1
        private const val OP_SET = 2
        private const val OP_SPLIT = 3
        private const val OP_JMP = 4
        private const val OP_SAVE = 5
        private const val OP_ASSERT = 6
        private const val OP_MATCH = 7
        private const val A_BOL = 0
        private const val A_EOL = 1
        private const val A_WORD_START = 2
        private const val A_WORD_END = 3

        /** The largest program and the largest repeat count: a pattern needing more is refused ([Compiled.TooBig]). */
        const val MAX_PROGRAM = 6000
        const val MAX_REPEAT = 1000

        // the character classes of a set: bit numbers
        private const val C_ALPHA = 0
        private const val C_DIGIT = 1
        private const val C_ALNUM = 2
        private const val C_LOWER = 3
        private const val C_UPPER = 4
        private const val C_SPACE = 5 // blank and tab only
        private const val C_WORD = 6 // [0-9A-Za-z_]
        private const val C_XDIGIT = 7
        private const val C_HEAD = 8 // [A-Za-z_]
        private const val C_PUNCT = 9
        private const val C_WS = 10 // [:space:]: every white space
        private const val C_BLANK = 11
        private const val C_KEYWORD = 12
        private const val U_ALPHA = 13 // the classes of [:alpha:] and the like know every letter, not only ASCII ones
        private const val U_UPPER = 14
        private const val U_LOWER = 15
        private const val U_ALNUM = 16

        private fun classTest(k: Int, cp: Int): Boolean = when (k) {
            C_ALPHA -> (cp in 'a'.code..'z'.code) || (cp in 'A'.code..'Z'.code)
            C_DIGIT -> cp in '0'.code..'9'.code
            C_ALNUM -> (cp in 'a'.code..'z'.code) || (cp in 'A'.code..'Z'.code) || (cp in '0'.code..'9'.code)
            C_LOWER -> cp in 'a'.code..'z'.code
            C_UPPER -> cp in 'A'.code..'Z'.code
            C_SPACE, C_BLANK -> cp == ' '.code || cp == '\t'.code
            C_WORD -> (cp in 'a'.code..'z'.code) || (cp in 'A'.code..'Z'.code) || (cp in '0'.code..'9'.code) || cp == '_'.code
            C_XDIGIT -> (cp in '0'.code..'9'.code) || (cp in 'a'.code..'f'.code) || (cp in 'A'.code..'F'.code)
            C_HEAD -> (cp in 'a'.code..'z'.code) || (cp in 'A'.code..'Z'.code) || cp == '_'.code
            C_PUNCT -> cp in 33..126 && !((cp in 'a'.code..'z'.code) || (cp in 'A'.code..'Z'.code) || (cp in '0'.code..'9'.code))
            C_WS -> cp == ' '.code || cp in 9..13
            C_KEYWORD -> isWord(cp)
            U_ALPHA -> Character.isLetter(cp)
            U_UPPER -> Character.isUpperCase(cp)
            U_LOWER -> Character.isLowerCase(cp)
            U_ALNUM -> Character.isLetterOrDigit(cp)
            else -> false
        }

        /** The keyword characters of Vim (letters, digits, underscore, and every character that is not punctuation, space or an emoji). */
        fun isWord(cp: Int): Boolean = VimBuf.classOf(cp, false) == 2

        /** Compiles a Vim pattern. [ignoreCase] is the default case rule; `\c` and `\C` in the pattern override it. */
        fun compile(pattern: String, ignoreCase: Boolean): Compiled {
            val p = Parser(pattern)
            val node = p.parse() ?: return if (p.tooBig) Compiled.TooBig else Compiled.Unsupported
            val ic = when {
                p.forceIgnore -> true
                p.forceMatch -> false
                else -> ignoreCase
            }
            val out = ArrayList<Inst>()
            out.add(Inst(OP_SAVE, 0))
            if (!emit(node, out)) return Compiled.TooBig
            out.add(Inst(OP_SAVE, 1))
            out.add(Inst(OP_MATCH))
            return Compiled.Ok(VimRegex(out.toTypedArray(), p.groups, ic))
        }

        private fun emit(node: Node, out: ArrayList<Inst>): Boolean {
            if (out.size > MAX_PROGRAM) return false
            when (node) {
                is Lit -> out.add(Inst(OP_CHAR, node.cp))
                AnyChar -> out.add(Inst(OP_ANY))
                is Cls -> out.add(Inst(OP_SET, set = node.set))
                is Assert -> out.add(Inst(OP_ASSERT, node.kind))
                is Seq -> for (i in node.items) if (!emit(i, out)) return false
                is Group -> {
                    if (node.index >= 0) out.add(Inst(OP_SAVE, 2 * node.index))
                    if (!emit(node.node, out)) return false
                    if (node.index >= 0) out.add(Inst(OP_SAVE, 2 * node.index + 1))
                }
                is Alt -> {
                    val jumps = ArrayList<Int>()
                    for ((k, b) in node.branches.withIndex()) {
                        if (k < node.branches.size - 1) {
                            val split = out.size
                            out.add(Inst(OP_SPLIT)) // patched below
                            if (!emit(b, out)) return false
                            jumps.add(out.size)
                            out.add(Inst(OP_JMP)) // patched below
                            out[split] = Inst(OP_SPLIT, split + 1, out.size)
                        } else if (!emit(b, out)) return false
                    }
                    for (j in jumps) out[j] = Inst(OP_JMP, out.size)
                }
                is Rep -> {
                    for (i in 0 until node.min) if (!emit(node.node, out)) return false
                    if (node.max < 0) { // unbounded: a loop
                        val split = out.size
                        out.add(Inst(OP_SPLIT))
                        if (!emit(node.node, out)) return false
                        out.add(Inst(OP_JMP, split))
                        out[split] = if (node.greedy) Inst(OP_SPLIT, split + 1, out.size) else Inst(OP_SPLIT, out.size, split + 1)
                    } else {
                        val splits = ArrayList<Int>()
                        for (i in node.min until node.max) {
                            splits.add(out.size)
                            out.add(Inst(OP_SPLIT))
                            if (!emit(node.node, out)) return false
                        }
                        for (s in splits) out[s] = if (node.greedy) Inst(OP_SPLIT, s + 1, out.size) else Inst(OP_SPLIT, out.size, s + 1)
                    }
                }
            }
            return out.size <= MAX_PROGRAM
        }
    }

    // ---- the parser -------------------------------------------------------------------------------------------------------

    private class Parser(val src: String) {
        var i = 0
        var groups = 0
        var tooBig = false
        var forceIgnore = false
        var forceMatch = false
        private var bad = false

        fun parse(): Node? {
            val n = alt(0)
            if (bad || i < src.length) return null // an unmatched \)
            return n
        }

        private fun more() = i < src.length

        private fun atBranchEnd(): Boolean {
            if (!more()) return true
            return src[i] == '\\' && i + 1 < src.length && (src[i + 1] == '|' || src[i + 1] == ')')
        }

        private fun alt(depth: Int): Node? {
            if (depth > 100) { tooBig = true; return null }
            val branches = ArrayList<Node>()
            while (true) {
                branches.add(seq(depth) ?: return null)
                if (more() && src[i] == '\\' && i + 1 < src.length && src[i + 1] == '|') { i += 2; continue }
                break
            }
            return if (branches.size == 1) branches[0] else Alt(branches)
        }

        private fun seq(depth: Int): Node? {
            val items = ArrayList<Node>()
            var atStart = true
            while (!atBranchEnd()) {
                var atom = atom(depth, atStart) ?: return null
                val startAnchor = atom is Assert && atom.kind == A_BOL && atStart
                if (atom is Assert && !startAnchor && more() && quantifier(peek = true) != null) return null // "\<*" is an error
                // a quantifier ("^*" has a literal star: the anchor takes none)
                while (!startAnchor && more()) {
                    val q = quantifier() ?: break
                    if (q.min > MAX_REPEAT || q.max > MAX_REPEAT) { tooBig = true; return null }
                    atom = Rep(atom, q.min, q.max, q.greedy)
                }
                if (bad) return null
                atStart = startAnchor
                items.add(atom)
            }
            return Seq(items)
        }

        private class Quant(val min: Int, val max: Int, val greedy: Boolean)

        /** Reads a quantifier at [i]; with [peek] it only says whether there is one (nothing is consumed). */
        private fun quantifier(peek: Boolean = false): Quant? {
            val save = i
            val q = readQuantifier()
            if (peek) i = save
            return q
        }

        private fun readQuantifier(): Quant? {
            val c = src[i]
            if (c == '*') { i++; return Quant(0, -1, true) }
            if (c != '\\' || i + 1 >= src.length) return null
            when (src[i + 1]) {
                '+' -> { i += 2; return Quant(1, -1, true) }
                '=', '?' -> { i += 2; return Quant(0, 1, true) }
                '{' -> {
                    var k = i + 2
                    var greedy = true
                    if (k < src.length && src[k] == '-') { greedy = false; k++ }
                    var min = 0
                    var max: Int
                    var any = false
                    while (k < src.length && src[k].isAsciiDigit()) { min = minOf(min * 10 + (src[k] - '0'), 100000); any = true; k++ }
                    if (k < src.length && src[k] == ',') {
                        k++
                        var n = 0
                        var anyMax = false
                        while (k < src.length && src[k].isAsciiDigit()) { n = minOf(n * 10 + (src[k] - '0'), 100000); anyMax = true; k++ }
                        max = if (anyMax) n else -1
                    } else max = if (any) min else -1 // \{n} is exactly n, \{} is *
                    if (k < src.length && src[k] == '\\') k++
                    if (k >= src.length || src[k] != '}') { bad = true; return null }
                    i = k + 1
                    if (max in 0 until min) { val t = min; min = max; max = t } // \{3,1} is \{1,3}
                    return Quant(min, max, greedy)
                }
            }
            return null
        }

        private fun Char.isAsciiDigit() = this in '0'..'9'

        private fun atom(depth: Int, atStart: Boolean): Node? {
            val c = src[i]
            if (c == '\\') return escape(depth)
            i++
            return when (c) {
                '.' -> AnyChar
                '^' -> if (atStart) Assert(A_BOL) else Lit('^'.code)
                '$' -> if (atBranchEnd()) Assert(A_EOL) else Lit('$'.code)
                '*' -> if (atStart) Lit('*'.code) else { bad = true; null }
                '[' -> bracket()
                else -> {
                    i--
                    val cp = src.codePointAt(i)
                    i += Character.charCount(cp)
                    Lit(cp)
                }
            }
        }

        private fun escape(depth: Int): Node? {
            if (i + 1 >= src.length) { bad = true; return null } // a lone backslash at the end
            val e = src[i + 1]
            i += 2
            return when (e) {
                '(' -> { groups++; val idx = groups; val n = alt(depth + 1) ?: return null; close() ?: return null; Group(n, idx) }
                '%' -> {
                    if (i < src.length && src[i] == '(') { i++; val n = alt(depth + 1) ?: return null; close() ?: return null; Group(n, -1) }
                    else { bad = true; null }
                }
                '<' -> Assert(A_WORD_START)
                '>' -> Assert(A_WORD_END)
                'c' -> { forceIgnore = true; Seq(emptyList()) }
                'C' -> { forceMatch = true; Seq(emptyList()) }
                's' -> cls(1 shl C_SPACE, false)
                'S' -> cls(1 shl C_SPACE, true)
                'd' -> cls(1 shl C_DIGIT, false)
                'D' -> cls(1 shl C_DIGIT, true)
                'w' -> cls(1 shl C_WORD, false)
                'W' -> cls(1 shl C_WORD, true)
                'a' -> cls(1 shl C_ALPHA, false)
                'A' -> cls(1 shl C_ALPHA, true)
                'l' -> cls(1 shl C_LOWER, false)
                'L' -> cls(1 shl C_LOWER, true)
                'u' -> cls(1 shl C_UPPER, false)
                'U' -> cls(1 shl C_UPPER, true)
                'x' -> cls(1 shl C_XDIGIT, false)
                'X' -> cls(1 shl C_XDIGIT, true)
                'h' -> cls(1 shl C_HEAD, false)
                'H' -> cls(1 shl C_HEAD, true)
                'k' -> cls(1 shl C_KEYWORD, false)
                'K' -> cls(1 shl C_KEYWORD, true)
                't' -> Lit('\t'.code)
                'e' -> Lit(27)
                'r' -> Lit('\r'.code)
                // outside the subset: a newline in the pattern, back references, \v \m \M \V, \zs \ze, \_x, a quantifier with nothing before it, the rest
                in '0'..'9', 'n', 'v', 'V', 'm', 'M', 'z', '_', '{', '}', '|', ')', '+', '=', '?', '&', '@', 'o', 'f', 'i', 'p', 'I', 'F', 'P', 'O' -> { bad = true; null }
                else -> Lit(e.code) // \. \* \\ \/ \[ \] \^ \$ \~ and any other character stand for themselves
            }
        }

        private fun close(): Unit? {
            if (i + 1 < src.length && src[i] == '\\' && src[i + 1] == ')') { i += 2; return Unit }
            bad = true
            return null
        }

        private fun cls(classes: Int, negated: Boolean): Node = Cls(CharSet(IntArray(0), negated, classes))

        private fun bracket(): Node? {
            // "[" without a closing "]" is a literal
            val start = i
            var j = i
            if (j < src.length && src[j] == '^') j++
            if (j < src.length && src[j] == ']') j++
            var closed = -1
            var k = j
            while (k < src.length) {
                val ch = src[k]
                if (ch == '\\' && k + 1 < src.length) { k += 2; continue }
                if (ch == '[' && k + 1 < src.length && src[k + 1] == ':') {
                    val end = src.indexOf(":]", k + 2)
                    if (end >= 0) { k = end + 2; continue }
                }
                if (ch == ']') { closed = k; break }
                k++
            }
            if (closed < 0) { i = start; return Lit('['.code) }
            var negated = false
            if (src[i] == '^') { negated = true; i++ }
            val ranges = ArrayList<Int>()
            var classes = 0
            var first = true
            while (i < closed) {
                var cp = src.codePointAt(i)
                if (cp == '['.code && i + 1 < closed && src[i + 1] == ':') {
                    val end = src.indexOf(":]", i + 2)
                    if (end in 0 until closed) {
                        val bit = when (src.substring(i + 2, end)) {
                            "alpha" -> U_ALPHA; "digit" -> C_DIGIT; "alnum" -> U_ALNUM; "lower" -> U_LOWER; "upper" -> U_UPPER
                            "space" -> C_WS; "blank" -> C_BLANK; "punct" -> C_PUNCT; "xdigit" -> C_XDIGIT
                            else -> { bad = true; return null }
                        }
                        classes = classes or (1 shl bit)
                        i = end + 2
                        first = false
                        continue
                    }
                }
                i += Character.charCount(cp)
                if (cp == '\\'.code && i < closed + 0) {
                    val e = src[i]
                    when (e) {
                        'e' -> { cp = 27; i++ }
                        't' -> { cp = '\t'.code; i++ }
                        'r' -> { cp = '\r'.code; i++ }
                        'b' -> { cp = 8; i++ }
                        '\\', ']', '^', '-' -> { cp = e.code; i++ }
                        else -> { /* the backslash stands for itself */ }
                    }
                }
                first = false
                var hi = cp
                if (i + 1 < closed && src[i] == '-') {
                    i++
                    hi = src.codePointAt(i)
                    i += Character.charCount(hi)
                    if (hi == '\\'.code && i < closed && src[i] in "e\\]^-tr") {
                        hi = when (src[i]) { 'e' -> 27; 't' -> 9; 'r' -> 13; else -> src[i].code }
                        i++
                    }
                    if (hi < cp) { bad = true; return null }
                } else if (i < closed && src[i] == '-' && i + 1 == closed) { /* a trailing "-" is a literal, handled on the next turn */ }
                ranges.add(cp)
                ranges.add(hi)
            }
            if (first && !negated) { /* "[]" cannot happen: a first "]" is a literal and is in the loop above */ }
            i = closed + 1
            return Cls(CharSet(ranges.toIntArray(), negated, classes))
        }
    }
}

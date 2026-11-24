package taen

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * Guard of the Vim package on the COMPILED classes (invariants P1, P11): every class and member that a class of helium314.keyboard.latin.vim
 * refers to must be on the allow list privacy/vim-bytecode.allow; everything else is denied. Backticked names, a typealias, an import alias, a
 * full name and an inlined library call compile to the same constant-pool references, so unlike a regex over the source nothing depends on the
 * spelling. It reads the class files of the Kotlin compiler (before R8, which could inline code out of the package). Pure functions, so they can
 * be unit tested.
 */
object VimBytecode {
    const val PACKAGE_DIR = "helium314/keyboard/latin/vim/"

    /**
     * The references of one class: classes (`java/io/File`) and members of classes with their descriptor (`java/io/File.delete:()Z`), every type
     * of a descriptor or signature included. [names] are the names that read as an internal class name (`java/io/File`) in a CONSTANT_Utf8 entry.
     * [unparsed] are the indexes of the entries that start as a type or signature and do not parse completely (see [references]).
     */
    class ClassRefs(val name: String, val refs: Set<String>, val names: Set<String> = emptySet(), val unparsed: List<Int> = emptyList())

    private const val CLASS = 7
    private const val FIELDREF = 9
    private const val METHODREF = 10
    private const val INTERFACE_METHODREF = 11
    private const val NAME_AND_TYPE = 12
    private const val METHOD_TYPE = 16
    private val descriptorType = Regex("L([^;]+);")
    private val internalName = Regex("""[\w$]+(?:/[\w$]+)+""")

    /** What an entry that is meant as a type or signature starts with: `Lpkg/Name;`, `Lpkg/`, an array of a type, a method signature `(`, type parameters `<T:`. */
    private val signatureStart = Regex("""^(?:L[\w$]+(?:/[\w$]+)*[;<.]|L[\w$]+/|\[+[BCDFIJSZ]|\[+L[\w$]+|\[+T[\w$]+|\(|<[\w$]+:)""")

    /** True when [text] is one complete signature (a type, a method descriptor or signature, a class signature) by the JVM grammar. */
    fun isCompleteSignature(text: String): Boolean = SignatureParser(text, 0, mutableSetOf()).complete()

    /**
     * The classes that the class type signatures in [text] name (annotations, Signature attributes, descriptors), by the full JVM signature grammar:
     * `Lpkg/Outer<args>.Inner<args>;` yields `pkg/Outer` and `pkg/Outer$Inner` (every nested segment, each with the names of its outer classes), and
     * the type arguments (`*`, `+T`, `-T`, a class type, a type variable, an array) are parsed for the classes they name. A text that is not a
     * complete signature yields the classes read before it breaks off (the outer class of a `L...;`, `L...<` or `L....`, and the nested ones).
     */
    fun signatureClasses(text: String): Set<String> {
        val found = linkedSetOf<String>()
        for (p in text.indices) {
            // an `L` that starts a type is not inside a word, a package path or a nested segment (those are read by the parse that is already running)
            if (text[p] == 'L' && (p == 0 || !(text[p - 1].isIdentifierChar() || text[p - 1] == '/' || text[p - 1] == '.'))) SignatureParser(text, p, found).classType()
        }
        return found
    }

    private fun Char.isIdentifierChar() = isLetterOrDigit() || this == '_' || this == '$'

    private class SignatureParser(val s: String, var i: Int, val out: MutableSet<String>) {
        private fun peek(): Char? = s.getOrNull(i)

        private fun identifier(): String? {
            val start = i
            while (i < s.length && s[i].isIdentifierChar()) i++
            return if (i > start) s.substring(start, i) else null
        }

        /** `L pkg/Name [<args>] (. Name [<args>])* ;` at [i]; true when it is complete. */
        fun classType(): Boolean {
            if (peek() != 'L') return false
            i++
            val path = StringBuilder(identifier() ?: return false)
            while (peek() == '/') { i++; path.append('/').append(identifier() ?: return false) }
            val names = mutableListOf(path.toString())
            // a class counts even when the rest is not a signature; a dot after a bare name ("Lorem.ipsum") is no nested segment of a class
            if (peek() == ';' || peek() == '<' || (peek() == '.' && '/' in names[0])) out.add(names[0])
            while (true) {
                if (peek() == '<' && !typeArguments()) return false
                when (peek()) {
                    '.' -> {
                        i++
                        names.add(names.last() + "$" + (identifier() ?: return false))
                        if (peek() == ';' || peek() == '<' || peek() == '.') out.add(names.last())
                    }
                    ';' -> { i++; out.addAll(names); return true }
                    else -> return false
                }
            }
        }

        fun complete(): Boolean = whole() && i == s.length

        /** A signature of any kind: optional type parameters, then a method (`(`), or the class types of a class or field signature, or one type. */
        private fun whole(): Boolean {
            if (peek() == '<' && !typeParameters()) return false
            return when (peek()) {
                '(' -> method()
                'L' -> { do { if (!classType()) return false } while (peek() == 'L'); true }
                else -> javaType()
            }
        }

        /** `<T:bound:interface U:...>`: the class bound may be empty. */
        private fun typeParameters(): Boolean {
            i++ // <
            var count = 0
            while (peek() != '>') {
                if (identifier() == null || peek() != ':') return false
                i++
                if (peek() == 'L' || peek() == 'T' || peek() == '[') { if (!reference()) return false }
                while (peek() == ':') { i++; if (!reference()) return false }
                count++
            }
            i++ // >
            return count > 0
        }

        /** `(` types `)` result (`V` or a type) and `^` throws. */
        private fun method(): Boolean {
            i++ // (
            while (peek() != ')') if (!javaType()) return false
            i++ // )
            if (peek() == 'V') i++ else if (!javaType()) return false
            while (peek() == '^') { i++; if (!reference()) return false }
            return true
        }

        private fun javaType(): Boolean {
            val c = peek()
            if (c != null && c in "BCDFIJSZ") { i++; return true }
            return reference()
        }

        private fun typeArguments(): Boolean {
            i++ // <
            var count = 0
            while (peek() != '>') {
                when (peek()) {
                    '*' -> i++
                    '+', '-' -> { i++; if (!reference()) return false }
                    else -> if (!reference()) return false
                }
                count++
            }
            i++ // >
            return count > 0
        }

        private fun reference(): Boolean = when (peek()) {
            'L' -> classType()
            'T' -> { i++; identifier() != null && peek() == ';' && run { i++; true } }
            '[' -> { i++; val c = peek(); if (c != null && c in "BCDFIJSZ") { i++; true } else reference() }
            else -> false
        }
    }

    /** Parses a class file and returns what its constant pool and its declared members refer to. Fails closed (IllegalArgumentException) on anything it does not understand. */
    fun references(bytes: ByteArray): ClassRefs {
        var pos = 0
        fun need(n: Int) = require(n >= 0 && pos + n <= bytes.size) { "class file ends early" }
        fun u1(): Int { need(1); return bytes[pos++].toInt() and 0xff }
        fun u2(): Int { need(2); val v = ((bytes[pos].toInt() and 0xff) shl 8) or (bytes[pos + 1].toInt() and 0xff); pos += 2; return v }
        fun u4(): Long { need(4); var v = 0L; repeat(4) { v = (v shl 8) or (bytes[pos++].toLong() and 0xff) }; return v }
        fun skip(n: Long) { require(n in 0..Int.MAX_VALUE) { "class file length out of range" }; need(n.toInt()); pos += n.toInt() }

        require(u4() == 0xCAFEBABEL) { "not a class file" }
        u2(); u2() // minor and major version
        val count = u2()
        val utf = arrayOfNulls<String>(count)
        val tag = IntArray(count)
        val a = IntArray(count)
        val b = IntArray(count)
        var i = 1
        while (i < count) {
            val t = u1()
            tag[i] = t
            when (t) {
                1 -> { val len = u2(); need(len); utf[i] = try { DataInputStream(ByteArrayInputStream(bytes, pos - 2, len + 2)).readUTF() } catch (e: java.io.IOException) { throw IllegalArgumentException("bad UTF-8 in the constant pool") }; pos += len }
                3, 4 -> skip(4)
                5, 6 -> { skip(8); i++ } // a long or double takes two slots
                CLASS, 8, METHOD_TYPE, 19, 20 -> a[i] = u2()
                FIELDREF, METHODREF, INTERFACE_METHODREF, NAME_AND_TYPE, 17, 18 -> { a[i] = u2(); b[i] = u2() }
                15 -> { u1(); u2() }
                else -> throw IllegalArgumentException("unknown constant pool tag $t")
            }
            i++
        }
        fun utf8(index: Int): String = require(index in 1 until count && tag[index] == 1) { "bad constant pool index $index" }.let { utf[index]!! }
        fun entry(index: Int, wanted: Int) = require(index in 1 until count && tag[index] == wanted) { "bad constant pool index $index" }

        val refs = linkedSetOf<String>()
        fun addDescriptor(d: String) { descriptorType.findAll(d).forEach { refs.add(it.groupValues[1]) } }
        fun addClass(name: String) { if (name.startsWith("[")) addDescriptor(name) else refs.add(name) }
        for (k in 1 until count) {
            when (tag[k]) {
                CLASS -> addClass(utf8(a[k]))
                FIELDREF, METHODREF, INTERFACE_METHODREF -> {
                    entry(a[k], CLASS); entry(b[k], NAME_AND_TYPE)
                    val owner = utf8(a[a[k]])
                    addClass(owner)
                    if (!owner.startsWith("[")) refs.add(owner + "." + utf8(a[b[k]]) + ":" + utf8(b[b[k]]))
                }
                NAME_AND_TYPE -> addDescriptor(utf8(b[k]))
                METHOD_TYPE -> addDescriptor(utf8(a[k]))
            }
        }
        // Every Utf8 entry counts, whatever attribute holds it (annotations, signatures, inner classes, a string constant): one that reads as a type
        // or as an internal class name is checked without parsing each attribute kind. Fail closed, by two rules:
        //  - an entry that is NOT a plain string constant (no CONSTANT_String names it) and starts as a type or signature must parse completely by the
        //    JVM grammar, else it is reported by its index in `unparsed` (the guard fails; a malformed signature could hide a denied class);
        //  - a plain string constant is text, not a signature: it may be anything, so when it is not a complete signature every slash-separated
        //    identifier token in it is checked as a class name (`see java/io/File`). A complete signature is checked by the classes it names.
        val plainStrings = (1 until count).filter { tag[it] == 8 }.map { a[it] }.toSet()
        val names = linkedSetOf<String>()
        val unparsed = mutableListOf<Int>()
        for (k in 1 until count) if (tag[k] == 1) {
            val text = utf[k]!!
            refs.addAll(signatureClasses(text))
            if (internalName.matches(text)) names.add(text)
            if (k in plainStrings) {
                if (!isCompleteSignature(text)) internalName.findAll(text).forEach { names.add(it.value) }
            } else if (signatureStart.containsMatchIn(text) && !isCompleteSignature(text)) {
                unparsed.add(k)
            }
        }
        u2() // access flags
        val thisIndex = u2()
        entry(thisIndex, CLASS)
        val thisName = utf8(a[thisIndex])
        u2() // super class: a Class entry like the others
        repeat(u2()) { u2() } // interfaces: Class entries like the others
        repeat(2) { // fields, then methods: their own descriptors are types the class names even when no code uses them
            repeat(u2()) {
                u2(); u2(); addDescriptor(utf8(u2()))
                repeat(u2()) { u2(); skip(u4()) }
            }
        }
        return ClassRefs(thisName, refs, names, unparsed)
    }

    /** `deny`/`allow` lines as in privacy/dex-rules.txt, with names in slash form. Anything not matched is denied. */
    fun parse(lines: List<String>): List<Pair<Boolean, String>> = ApkRules.parse(lines).entries

    /**
     * The rule that decides [ref], or null when none matches (then it is denied). An entry matches a reference that is the same name, a member
     * of it (`Name.member`), an inner class of it (`Name$Inner`), or, when the entry ends with `/` or `.`, anything in that package or below,
     * or any member (so `allow Name` with `deny Name.` allows the type and no member of it, and `allow Name.member` makes one exception). A
     * member reference is `Name.member:descriptor`, so `allow Name.member:descriptor` allows one overload only. The longest match decides; on a
     * tie `deny` wins.
     */
    fun decide(ref: String, entries: List<Pair<Boolean, String>>): Pair<Boolean, String>? {
        val matching = entries.filter { (_, e) -> ref == e || ref.startsWith("$e.") || ref.startsWith("$e$") || ref.startsWith("$e:") || ((e.endsWith("/") || e.endsWith(".")) && ref.startsWith(e)) }
        val longest = matching.maxOfOrNull { it.second.length } ?: return null
        val best = matching.filter { it.second.length == longest }
        return best.firstOrNull { it.first } ?: best.first() // (deny, name)
    }

    fun allowed(ref: String, entries: List<Pair<Boolean, String>>): Boolean = decide(ref, entries)?.first == false

    /** The class files of the package found in [scanned] outputs, and the problems found in [everything] (every compiled output of the program). */
    class Collected(val classes: Map<String, ByteArray>, val problems: List<String>)

    private val versioned = Regex("""^META-INF/versions/\d+/""")

    /**
     * The guard covers every class of the package, whatever produced it: the classes of the [scanned] directories (the outputs of the Kotlin
     * compiler and of javac) are returned for [check]; a class of the package in any other output among [everything] (a directory that is not
     * one of the scanned, a jar) is a problem, since nothing checked it. A directory counts as scanned when it is the same directory.
     */
    fun collect(scanned: List<File>, everything: List<File>): Collected {
        val found = linkedMapOf<String, ByteArray>()
        val scannedDirs = scanned.filter { it.isDirectory }.map { it.canonicalFile }
        for (root in scannedDirs) {
            root.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { f ->
                val rel = f.relativeTo(root).invariantSeparatorsPath
                if (rel.startsWith(PACKAGE_DIR)) found[if (rel in found) "$rel (in ${root.path})" else rel] = f.readBytes()
            }
        }
        val problems = mutableListOf<String>()
        for (output in everything.filter { it.exists() }) {
            val canonical = output.canonicalFile
            if (canonical.isDirectory) {
                if (scannedDirs.any { canonical == it }) continue
                canonical.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { f ->
                    val rel = f.relativeTo(canonical).invariantSeparatorsPath
                    if (rel.startsWith(PACKAGE_DIR)) problems.add("$rel is in ${output.path}, a compiled output that the vim guard does not scan")
                }
            } else {
                try {
                    ZipFile(canonical).use { z ->
                        for (e in z.entries()) {
                            if (e.name.endsWith(".class") && versioned.replaceFirst(e.name, "").startsWith(PACKAGE_DIR))
                                problems.add("${e.name} is in ${output.path}, a compiled output that the vim guard does not scan")
                        }
                    }
                } catch (e: java.io.IOException) { problems.add("${output.path} cannot be read as a jar (${e.message})") }
            }
        }
        return Collected(found, problems)
    }

    /** Problems found in the classes ([name] to bytes): every reference that is not allowed, once per class and reference. */
    fun check(classes: Map<String, ByteArray>, entries: List<Pair<Boolean, String>>): List<String> {
        val problems = mutableListOf<String>()
        if (classes.isEmpty()) problems.add("no class of the vim package found, nothing was scanned")
        for ((file, bytes) in classes) {
            val parsed = try { references(bytes) } catch (e: IllegalArgumentException) { problems.add("$file: unreadable class file (${e.message})"); continue }
            for (ref in parsed.refs) if (!allowed(ref, entries)) problems.add("${parsed.name} refers to $ref, which is not on the vim allow list")
            for (n in parsed.names) if (n !in parsed.refs && !allowed(n, entries)) problems.add("${parsed.name} has the constant $n, which reads as a class name that is not on the vim allow list")
            // the index, never the text of the entry, is in the message
            for (k in parsed.unparsed) problems.add("${parsed.name} has constant pool entry #$k, which starts as a type signature but does not parse completely")
        }
        return problems
    }
}

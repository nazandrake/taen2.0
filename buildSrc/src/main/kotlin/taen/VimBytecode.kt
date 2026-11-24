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
     * of a descriptor or signature included. [names] are the CONSTANT_Utf8 entries that read as an internal class name (`java/io/File`).
     */
    class ClassRefs(val name: String, val refs: Set<String>, val names: Set<String> = emptySet())

    private const val CLASS = 7
    private const val FIELDREF = 9
    private const val METHODREF = 10
    private const val INTERFACE_METHODREF = 11
    private const val NAME_AND_TYPE = 12
    private const val METHOD_TYPE = 16
    private val descriptorType = Regex("L([^;]+);")
    // a type inside any descriptor or generic signature (annotations, Signature attributes, method and field descriptors): L<name>; or L<name><
    private val signatureType = Regex("""(?<![\w$/])L([\w$]+(?:/[\w$]+)*)[;<]""")
    private val internalName = Regex("""[\w$]+(?:/[\w$]+)+""")

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
        // or as an internal class name is checked without parsing each attribute kind. Fail closed: a string constant that looks like a class
        // name is checked as one.
        val names = linkedSetOf<String>()
        for (k in 1 until count) if (tag[k] == 1) {
            val text = utf[k]!!
            signatureType.findAll(text).forEach { refs.add(it.groupValues[1]) }
            if (internalName.matches(text)) names.add(text)
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
        return ClassRefs(thisName, refs, names)
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
        }
        return problems
    }
}

package taen

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VimBytecodeTest {
    /** A class file built by hand, so that a test controls exactly what the constant pool and the members say. */
    private class ClassFile(val name: String) {
        private val pool = ByteArrayOutputStream()
        private val out = DataOutputStream(pool)
        private var count = 1
        private val fields = mutableListOf<Pair<String, String>>()
        private val methods = mutableListOf<Pair<String, String>>()
        private var rawTag: Int? = null

        fun utf(s: String): Int { out.writeByte(1); out.writeUTF(s); return count++ }
        fun cls(n: String): Int { val u = utf(n); out.writeByte(7); out.writeShort(u); return count++ }
        fun string(s: String): Int { val u = utf(s); out.writeByte(8); out.writeShort(u); return count++ }
        fun long(): Int { out.writeByte(5); out.writeLong(1); val i = count; count += 2; return i }
        fun nameAndType(n: String, d: String): Int { val a = utf(n); val b = utf(d); out.writeByte(12); out.writeShort(a); out.writeShort(b); return count++ }
        fun methodType(d: String): Int { val u = utf(d); out.writeByte(16); out.writeShort(u); return count++ }
        private fun ref(tag: Int, owner: String, n: String, d: String): Int { val c = cls(owner); val nt = nameAndType(n, d); out.writeByte(tag); out.writeShort(c); out.writeShort(nt); return count++ }
        fun method(owner: String, n: String, d: String) = ref(10, owner, n, d)
        fun field(owner: String, n: String, d: String) = ref(9, owner, n, d)
        fun interfaceMethod(owner: String, n: String, d: String) = ref(11, owner, n, d)
        fun invokeDynamic(n: String, d: String): Int { val nt = nameAndType(n, d); out.writeByte(18); out.writeShort(0); out.writeShort(nt); return count++ }
        fun declareField(n: String, d: String) { fields.add(n to d) }
        fun declareMethod(n: String, d: String) { methods.add(n to d) }
        fun unknownTag(tag: Int) { rawTag = tag }

        fun bytes(): ByteArray {
            val thisIndex = cls(name)
            val superIndex = cls("java/lang/Object")
            val declared = (fields + methods).map { utf(it.first) to utf(it.second) }
            val pending = rawTag
            val body = ByteArrayOutputStream()
            val o = DataOutputStream(body)
            o.writeInt(0xCAFEBABE.toInt()); o.writeShort(0); o.writeShort(61)
            o.writeShort(count + (if (pending != null) 1 else 0))
            o.write(pool.toByteArray())
            if (pending != null) o.writeByte(pending)
            o.writeShort(0x21); o.writeShort(thisIndex); o.writeShort(superIndex); o.writeShort(0)
            o.writeShort(fields.size)
            for (i in fields.indices) { o.writeShort(1); o.writeShort(declared[i].first); o.writeShort(declared[i].second); o.writeShort(1); o.writeShort(declared[i].first); o.writeInt(2); o.writeShort(0) }
            o.writeShort(methods.size)
            for (i in methods.indices) { val d = declared[fields.size + i]; o.writeShort(1); o.writeShort(d.first); o.writeShort(d.second); o.writeShort(0) }
            o.writeShort(0)
            return body.toByteArray()
        }
    }

    private fun refs(c: ClassFile) = VimBytecode.references(c.bytes()).refs

    // ---- the parser

    @Test fun readsTheReferencesOfARealCompiledClass() {
        val bytes = VimBytecode::class.java.getResourceAsStream("/taen/VimBytecode.class")!!.use { it.readBytes() }
        val parsed = VimBytecode.references(bytes)
        assertEquals("taen/VimBytecode", parsed.name)
        for (expected in listOf("java/io/DataInputStream", "java/io/DataInputStream.readUTF", "java/io/ByteArrayInputStream", "kotlin/jvm/internal/Intrinsics", "taen/ApkRules", "taen/ApkRules.parse"))
            assertTrue("$expected in ${parsed.refs}", expected in parsed.refs)
        assertFalse("java/io/File" in parsed.refs)
    }

    @Test fun aMethodFieldAndInterfaceMethodNameTheirOwnerAndMember() {
        val c = ClassFile("a/B")
        c.method("java/io/RandomAccessFile", "write", "([B)V")
        c.field("java/lang/System", "out", "Ljava/io/PrintStream;")
        c.interfaceMethod("java/util/List", "size", "()I")
        val r = refs(c)
        for (e in listOf("java/io/RandomAccessFile", "java/io/RandomAccessFile.write", "java/lang/System", "java/lang/System.out", "java/io/PrintStream", "java/util/List", "java/util/List.size", "a/B", "java/lang/Object"))
            assertTrue("$e in $r", e in r)
    }

    @Test fun everyTypeInAnyDescriptorIsAReference() {
        val c = ClassFile("a/B")
        c.method("a/C", "m", "(Ljava/io/File;I[[Ljava/net/Socket;)Landroid/util/Log;")
        c.nameAndType("x", "Ljava/lang/Process;")
        c.methodType("(Ljava/lang/ProcessBuilder;)V")
        c.invokeDynamic("run", "(Ljava/lang/Thread;)Ljava/lang/Runnable;")
        c.declareField("f", "[Ljava/io/FileWriter;")
        c.declareMethod("g", "(Ljava/lang/Runtime;)V")
        val r = refs(c)
        for (e in listOf("java/io/File", "java/net/Socket", "android/util/Log", "java/lang/Process", "java/lang/ProcessBuilder", "java/lang/Thread", "java/lang/Runnable", "java/io/FileWriter", "java/lang/Runtime"))
            assertTrue("$e in $r", e in r)
    }

    @Test fun anArrayClassConstantNamesItsElementTypeAndNoMember() {
        val c = ClassFile("a/B")
        c.method("[Ljava/io/File;", "clone", "()Ljava/lang/Object;")
        val r = refs(c)
        assertTrue("java/io/File" in r)
        assertTrue(r.none { it.startsWith("[") })
        assertTrue(r.none { it.startsWith("java/io/File.") })
    }

    @Test fun aStringConstantIsNotAReferenceAndALongTakesTwoSlots() {
        val c = ClassFile("a/B")
        c.string("java.io.File")
        c.string("Ljava/io/File;")
        c.long()
        c.cls("java/util/Locale") // parsed correctly only if the long above took two slots
        val r = refs(c)
        assertTrue("java/util/Locale" in r)
        assertFalse("java/io/File" in r)
        assertFalse(r.any { it.contains('.') })
    }

    @Test fun aClassFileThatCannotBeUnderstoodIsAnError() {
        val good = ClassFile("a/B").also { it.cls("java/util/Locale") }.bytes()
        for ((what, bytes) in listOf("empty" to ByteArray(0), "bad magic" to good.copyOf().also { it[0] = 0 }, "truncated" to good.copyOf(good.size - 3), "cut in the pool" to good.copyOf(20),
            "unknown tag" to ClassFile("a/B").also { it.unknownTag(2) }.bytes())) {
            try { VimBytecode.references(bytes); fail("$what: no error") } catch (e: IllegalArgumentException) { /* fail closed */ }
        }
        VimBytecode.references(good) // positive control
    }

    // ---- the rules

    private fun rules(vararg lines: String) = VimBytecode.parse(lines.toList())

    @Test fun anEntryMatchesTheNameItsMembersAndItsInnerClassesButNotALongerName() {
        val r = rules("allow java/lang/Process")
        assertTrue(VimBytecode.allowed("java/lang/Process", r))
        assertTrue(VimBytecode.allowed("java/lang/Process.destroy", r))
        assertTrue(VimBytecode.allowed("java/lang/Process\$Inner", r))
        assertFalse("a longer name is another class", VimBytecode.allowed("java/lang/ProcessBuilder", r))
        assertFalse(VimBytecode.allowed("java/lang/Proc", r))
    }

    @Test fun aSlashEntryCoversAPackageAndEverythingBelow() {
        val r = rules("allow kotlin/", "deny kotlin/io/")
        assertTrue(VimBytecode.allowed("kotlin/Pair", r))
        assertTrue(VimBytecode.allowed("kotlin/collections/CollectionsKt.contains", r))
        assertFalse(VimBytecode.allowed("kotlin/io/ConsoleKt", r))
        assertFalse(VimBytecode.allowed("kotlin/io/ConsoleKt.println", r))
        assertFalse("kotlin/ itself is not a class of the package", VimBytecode.allowed("kotlinx/Foo", r))
    }

    @Test fun aDotEntryDeniesTheMembersAndLeavesTheTypeAndAnExceptionalMember() {
        val r = rules("allow java/lang/Class", "deny java/lang/Class.", "allow java/lang/Class.getName")
        assertTrue(VimBytecode.allowed("java/lang/Class", r))
        assertFalse(VimBytecode.allowed("java/lang/Class.forName", r))
        assertTrue(VimBytecode.allowed("java/lang/Class.getName", r))
    }

    @Test fun whatIsNotListedIsDeniedAndATieIsADeny() {
        val r = rules("allow a/B", "deny a/B")
        assertFalse(VimBytecode.allowed("a/B", r))
        assertFalse(VimBytecode.allowed("c/D", r))
        assertEquals(null, VimBytecode.decide("c/D", r))
        assertFalse(VimBytecode.allowed("anything", emptyList()))
    }

    @Test fun theRuleFileSyntaxIsTheDexRulesSyntax() {
        assertEquals(listOf(true to "a/B", false to "c/"), VimBytecode.parse(listOf("# comment", "deny a/B  # why", "", "allow c/")))
        try { VimBytecode.parse(listOf("permit a/B")); fail("a bad line is an error") } catch (e: IllegalArgumentException) { /* fail closed */ }
    }

    // ---- check(): problems per class and reference

    @Test fun checkNamesTheClassAndTheReferenceAndAnEmptyScanIsAProblem() {
        val c = ClassFile("helium314/keyboard/latin/vim/X")
        c.method("java/io/File", "delete", "()Z")
        val r = rules("allow helium314/keyboard/latin/vim/", "allow java/lang/Object")
        assertEquals(
            listOf("helium314/keyboard/latin/vim/X refers to java/io/File, which is not on the vim allow list", "helium314/keyboard/latin/vim/X refers to java/io/File.delete, which is not on the vim allow list"),
            VimBytecode.check(mapOf("helium314/keyboard/latin/vim/X.class" to c.bytes()), r))
        assertEquals(emptyList<String>(), VimBytecode.check(mapOf("helium314/keyboard/latin/vim/Y.class" to ClassFile("helium314/keyboard/latin/vim/Y").bytes()), r))
        assertEquals(1, VimBytecode.check(emptyMap(), r).size)
        val broken = VimBytecode.check(mapOf("helium314/keyboard/latin/vim/Z.class" to ByteArray(10)), r)
        assertEquals(1, broken.size)
        assertTrue(broken[0], broken[0].contains("unreadable class file"))
    }

    // ---- the committed allow list against references of the planted lines

    private val committed = VimBytecode.parse(File("../privacy/vim-bytecode.allow").readLines())

    private fun verdict(build: ClassFile.() -> Unit): List<String> =
        VimBytecode.check(mapOf("helium314/keyboard/latin/vim/P.class" to ClassFile("helium314/keyboard/latin/vim/P").also { it.build() }.bytes()), committed)

    @Test fun theCommittedAllowListAcceptsWhatTheVimPackageNeedsToday() {
        assertEquals(emptyList<String>(), verdict {
            method("java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;")
            method("java/lang/Math", "abs", "(I)I")
            method("kotlin/math/MathKt", "abs", "(I)I") // the positive control of the planted lines: kotlin.math.abs
            method("kotlin/jvm/internal/Intrinsics", "checkNotNullParameter", "(Ljava/lang/Object;Ljava/lang/String;)V")
            method("java/text/BreakIterator", "getCharacterInstance", "(Ljava/util/Locale;)Ljava/text/BreakIterator;")
            method("java/lang/Enum", "valueOf", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;") // the compiler's enum code names Class in a descriptor
            field("kotlin/collections/CollectionsKt", "x", "Ljava/util/List;")
            method("java/util/ArrayList", "add", "(Ljava/lang/Object;)Z")
            method("java/lang/invoke/StringConcatFactory", "makeConcatWithConstants", "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;")
            method("java/lang/invoke/LambdaMetafactory", "metafactory", "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;")
        })
    }

    @Test fun theCommittedAllowListDeniesEachWayOfReachingAFileANetworkALogAProcessAThreadOrReflection() {
        val denied = mapOf<String, ClassFile.() -> Unit>(
            "a backticked RandomAccessFile write" to { method("java/io/RandomAccessFile", "<init>", "(Ljava/lang/String;Ljava/lang/String;)V"); method("java/io/RandomAccessFile", "write", "([B)V") },
            "a typealias to java.io.File" to { method("java/io/File", "<init>", "(Ljava/lang/String;)V") },
            "a File that is only named in a signature" to { declareField("f", "Ljava/io/File;") },
            "an import alias of android.util.Log" to { method("android/util/Log", "d", "(Ljava/lang/String;Ljava/lang/String;)I") },
            "ProcessBuilder" to { method("java/lang/ProcessBuilder", "start", "()Ljava/lang/Process;") },
            "Runtime.exec" to { method("java/lang/Runtime", "exec", "(Ljava/lang/String;)Ljava/lang/Process;") },
            "Class.forName" to { method("java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;") },
            "Object.getClass" to { method("java/lang/Object", "getClass", "()Ljava/lang/Class;") },
            "println" to { method("kotlin/io/ConsoleKt", "println", "(Ljava/lang/Object;)V") },
            "System.out" to { field("java/lang/System", "out", "Ljava/io/PrintStream;") },
            "a Thread" to { method("java/lang/Thread", "start", "()V") },
            "kotlin.concurrent.thread" to { method("kotlin/concurrent/ThreadsKt", "thread", "()Ljava/lang/Thread;") },
            "exitProcess" to { method("kotlin/system/ProcessKt", "exitProcess", "(I)Ljava/lang/Void;") },
            "a class loader" to { method("java/lang/ClassLoader", "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;") },
            "java.lang.reflect" to { method("java/lang/reflect/Method", "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;") },
            "a KClass" to { method("kotlin/jvm/internal/Reflection", "getOrCreateKotlinClass", "(Ljava/lang/Class;)Lkotlin/reflect/KClass;") },
            "java.net" to { method("java/net/URL", "openStream", "()Ljava/io/InputStream;") },
            "java.nio" to { method("java/nio/file/Files", "readAllBytes", "(Ljava/nio/file/Path;)[B") },
            "a method handle lookup" to { method("java/lang/invoke/MethodHandles", "lookup", "()Ljava/lang/invoke/MethodHandles\$Lookup;") },
            "a method handle lookup member" to { method("java/lang/invoke/MethodHandles\$Lookup", "findStatic", "()V") },
            "printStackTrace" to { method("java/lang/Throwable", "printStackTrace", "()V") },
            "error(registerText), which inlines to an exception with the text as its message" to { method("java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V") },
            "require(...) { text }" to { method("java/lang/IllegalArgumentException", "<init>", "(Ljava/lang/String;)V") },
            "a thrown NullPointerException with a message" to { method("java/lang/NullPointerException", "<init>", "(Ljava/lang/String;)V") },
            "an Android class" to { method("android/content/Context", "getSystemService", "(Ljava/lang/String;)Ljava/lang/Object;") },
            "a class of the keyboard outside the package" to { method("helium314/keyboard/latin/privacy/Privacy", "lock", "()V") },
            "a library class" to { method("kotlinx/serialization/json/Json", "encode", "()V") },
        )
        for ((what, build) in denied) assertTrue("$what must be refused", verdict(build).isNotEmpty())
        // the positive control for the whole table: the same helper accepts a clean class
        assertEquals(emptyList<String>(), verdict { method("kotlin/math/MathKt", "abs", "(I)I") })
    }
}

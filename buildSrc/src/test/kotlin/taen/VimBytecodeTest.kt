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
        for (expected in listOf("java/io/DataInputStream", "java/io/DataInputStream.readUTF:()Ljava/lang/String;", "java/io/ByteArrayInputStream", "kotlin/jvm/internal/Intrinsics", "taen/ApkRules", "taen/ApkRules.parse:(Ljava/util/List;)Ltaen/ApkRules\$Rules;"))
            assertTrue("$expected in ${parsed.refs}", expected in parsed.refs)
        assertFalse("java/net/Socket" in parsed.refs) // the guard itself reads files now, but opens no socket
    }

    @Test fun aMethodFieldAndInterfaceMethodNameTheirOwnerAndMember() {
        val c = ClassFile("a/B")
        c.method("java/io/RandomAccessFile", "write", "([B)V")
        c.field("java/lang/System", "out", "Ljava/io/PrintStream;")
        c.interfaceMethod("java/util/List", "size", "()I")
        val r = refs(c)
        for (e in listOf("java/io/RandomAccessFile", "java/io/RandomAccessFile.write:([B)V", "java/lang/System", "java/lang/System.out:Ljava/io/PrintStream;", "java/io/PrintStream", "java/util/List", "java/util/List.size:()I", "a/B", "java/lang/Object"))
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

    @Test fun aDottedStringIsNotAReferenceAndALongTakesTwoSlots() {
        val c = ClassFile("a/B")
        c.string("java.io.File")
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
            listOf("helium314/keyboard/latin/vim/X refers to java/io/File, which is not on the vim allow list", "helium314/keyboard/latin/vim/X refers to java/io/File.delete:()Z, which is not on the vim allow list"),
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
            utf("Lorg/jetbrains/annotations/NotNull;") // annotations that the compiler writes, named only in the attributes
            utf("Lorg/jetbrains/annotations/Nullable;")
            utf("Landroidx/compose/runtime/internal/StabilityInferred;")
            utf("Ljava/util/List<Ljava/lang/Integer;>;")
            method("kotlin/NoWhenBranchMatchedException", "<init>", "()V")
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

    // ---- attribute-only types and the exception helpers of the compiler

    @Test fun aTypeNamedOnlyInAnAnnotationIsChecked() {
        // RuntimeVisibleAnnotations hold the descriptor as a CONSTANT_Utf8 and no Class entry names the type
        val problems = verdict { utf("Lbad/Annotation;") }
        assertEquals(listOf("helium314/keyboard/latin/vim/P refers to bad/Annotation, which is not on the vim allow list"), problems)
    }

    // ---- generic signatures: nested segments, type arguments, wildcards, type variables, arrays

    private fun signatureRefs(vararg texts: String) = refs(ClassFile("a/B").also { c -> texts.forEach { c.utf(it) } }) - setOf("a/B", "java/lang/Object")

    @Test fun aNestedSegmentOfAGenericSignatureNamesTheOuterAndTheNestedClass() {
        // a field of type ObjectInputStream.GetField<String>: the nested form does not repeat the outer name before a `;` or a `<`
        assertEquals(setOf("java/io/ObjectInputStream", "java/io/ObjectInputStream\$GetField", "java/lang/String"), signatureRefs("Ljava/io/ObjectInputStream.GetField<Ljava/lang/String;>;"))
        assertEquals(setOf("java/io/ObjectInputStream", "java/io/ObjectInputStream\$GetField"), signatureRefs("Ljava/io/ObjectInputStream.GetField;"))
        val problems = verdict { utf("Ljava/io/ObjectInputStream.GetField<Ljava/lang/String;>;") }
        assertTrue(problems.toString(), problems.any { it.contains("refers to java/io/ObjectInputStream, which") })
        assertTrue(problems.toString(), problems.any { it.contains("refers to java/io/ObjectInputStream\$GetField, which") })
    }

    @Test fun theWholeSignatureGrammarYieldsEveryClassName() {
        assertEquals(setOf("a/Outer", "a/Outer\$Mid", "a/Outer\$Mid\$In", "x/Arg", "y/Arg2"), signatureRefs("La/Outer<Lx/Arg;>.Mid<Ly/Arg2;>.In;"))
        assertEquals(setOf("java/util/Map", "java/io/File", "java/net/Socket", "java/lang/Process", "java/lang/Thread"), signatureRefs("Ljava/util/Map<+Ljava/io/File;-Ljava/net/Socket;>;", "<T:Ljava/lang/Process;>([[Ljava/lang/Thread;TT;)V"))
        assertEquals(setOf("java/util/List", "java/io/File"), signatureRefs("Ljava/util/List<*>;", "[Ljava/io/File;"))
        assertEquals(setOf("java/util/List", "java/io/File"), signatureRefs("Ljava/util/List<[Ljava/io/File;>;"))
        // a type variable is not a class, and nothing else that merely begins with L is
        assertEquals(emptySet<String>(), signatureRefs("TLjava;", "<L:I>(TL;)V", "Lorem.ipsum", "Lorem ipsum.dolor;"))
        assertEquals(setOf("java/lang/Number"), signatureRefs("<L:Ljava/lang/Number;>(TL;)V")) // a type parameter named L
        assertEquals(setOf("a/Outer", "a/Outer\$Lazy"), signatureRefs("La/Outer.Lazy;")) // a nested class whose name starts with L is no class of its own
        // every kind of type argument before a nested segment is parsed through, so the nested class is still found
        for (arg in listOf("*", "+Lx/A;", "-Lx/A;", "TT;", "Lx/A;", "Lx/A<Ly/B;>;", "[I", "[[Lx/A;", "Lx/A;*"))
            assertTrue("a/O\$In for <$arg>", "a/O\$In" in signatureRefs("La/O<$arg>.In;"))
        assertEquals(setOf("foo/Bar"), signatureRefs("Lfoo/Bar<weird")) // not a signature: its outer class counts all the same
        assertEquals(setOf("a/O"), signatureRefs("La/O<>.In;")) // empty type arguments are not a signature: no nested class from it
        assertEquals(setOf("a/O", "x/A"), signatureRefs("La/O<Lx/A;")) // not closed: the outer class and the argument count
    }

    @Test fun aNestedTypeThatIsAllowedStaysAllowed() {
        // kotlin.collections.Map.Entry: an inner class of an allowed class, named in a Signature attribute
        assertEquals(emptyList<String>(), verdict { utf("Lkotlin/collections/Map.Entry<Ljava/lang/String;Ljava/lang/Integer;>;"); utf("Ljava/util/List<Lkotlin/collections/Map\$Entry<Ljava/lang/String;Ljava/lang/Integer;>;>;") })
        assertEquals(setOf("kotlin/collections/Map", "kotlin/collections/Map\$Entry", "java/lang/String", "java/lang/Integer"), signatureRefs("Lkotlin/collections/Map.Entry<Ljava/lang/String;Ljava/lang/Integer;>;"))
        // the control: the same shape with a denied outer class is refused
        assertTrue(verdict { utf("Ljava/io/File.Inner<Ljava/lang/String;>;") }.isNotEmpty())
    }

    // ---- a signature that does not parse completely: fail closed

    private val notComplete = ", which starts as a type signature but does not parse completely"

    @Test fun aMalformedNestedDeniedClassInASignatureIsNotSkipped() {
        // the nested segment of a denied class that is never closed: the outer class and the nested one are named all the same
        assertEquals(setOf("java/util/List", "bad/Denied", "bad/Denied\$Inner"), signatureRefs("Ljava/util/List<Lbad/Denied.Inner<"))
        assertEquals(setOf("bad/Denied", "bad/Denied\$Inner"), signatureRefs("Lbad/Denied.Inner<"))
        assertEquals(setOf("bad/Denied"), signatureRefs("Lbad/Denied.")) // cut off after the dot
        val problems = verdict { utf("Ljava/util/List<Lbad/Denied.Inner<") }
        assertTrue(problems.toString(), problems.any { it.contains("refers to bad/Denied, which") })
        assertTrue(problems.toString(), problems.any { it.contains("refers to bad/Denied\$Inner, which") })
        assertTrue(problems.toString(), problems.any { it.endsWith(notComplete) })
    }

    @Test fun anEntryThatStartsAsASignatureAndDoesNotParseCompletelyFailsWithItsIndexAndNotItsText() {
        val broken = listOf("Ljava/util/List<", "Lfoo/Bar;extra", "(Ljava/lang/String;", "[Lx/Y", "<T:Ljava/lang/Object;", "Lfoo/Bar<>;", "[Lfoo/", "(I)", "(ILfoo/Bar;)Vx", "(I)V^", "Lx/Y", "[Bx", "Ljava/util/List<Lbad/Denied.Inner<")
        for (text in broken) {
            var index = 0
            val problems = verdict { index = utf(text) }
            val expected = "helium314/keyboard/latin/vim/P has constant pool entry #$index$notComplete"
            assertTrue("$text: $problems", expected in problems)
            assertTrue("$text: the message holds no text of the entry", problems.none { it.contains(text) && it.endsWith(notComplete) })
        }
        // positive controls: complete signatures of every kind, and entries that do not start as one
        val complete = listOf("Ljava/util/List<Ljava/lang/Integer;>;", "(Ljava/lang/String;I)V", "()V", "(IJ[[Ljava/lang/String;)[I", "[I", "[[Ljava/lang/String;", "Ljava/lang/Object;",
            "<T:Ljava/lang/Object;>(TT;[I)Ljava/util/List<TT;>;", "<T::Ljava/lang/CharSequence;>(TT;)TT;", "<T:Ljava/lang/Object;U::Ljava/lang/CharSequence;>Ljava/lang/Object;Ljava/lang/Iterable<TT;>;",
            "Lkotlin/collections/Map.Entry<Ljava/lang/String;+Ljava/lang/Integer;>;", "()Ljava/util/List<*>;", "<init>", "<clinit>", "Lorem ipsum", "plain text", "", "[x", "Lazy")
        for (text in complete) assertEquals(text, emptyList<String>(), verdict { utf(text) })
    }

    @Test fun aStringConstantThatDoesNotParseHasEverySlashSeparatedTokenCheckedAsAClassName() {
        val x = "helium314/keyboard/latin/vim/X"
        fun problems(text: String) = VimBytecode.check(mapOf("$x.class" to ClassFile(x).also { it.string(text) }.bytes()), committed)
        // the string is not a signature, and no entry starts as one, so there is no failure for the entry: only the names in it count
        assertEquals(listOf("$x has the constant java/io/File, which reads as a class name that is not on the vim allow list"), problems("see java/io/File and more"))
        assertEquals(listOf("$x has the constant java/io/File, which reads as a class name that is not on the vim allow list", "$x has the constant java/net/Socket, which reads as a class name that is not on the vim allow list"),
            problems("a java/io/File, then java/net/Socket."))
        val malformed = problems("Ljava/util/List<Lbad/Denied.Inner<")
        assertTrue(malformed.toString(), malformed.any { it.contains("bad/Denied") })
        assertTrue(malformed.toString(), malformed.none { it.endsWith(notComplete) })
        // positive controls: allowed names, and text without a slash-separated token
        assertEquals(emptyList<String>(), problems("see java/util/Locale and helium314/keyboard/latin/vim/VimBuf"))
        assertEquals(emptyList<String>(), problems("Lorem ipsum dolor, sit amet"))
        // a string constant that is a complete signature is checked by it
        assertEquals(listOf("$x refers to java/io/File, which is not on the vim allow list"), problems("Ljava/io/File;"))
    }

    // ---- everything a Utf8 entry can say, the members that take a message

    @Test fun aTypeInAGenericSignatureOrAnUnpackagedTypeOrAStringThatLooksLikeOneIsChecked() {
        val r = refs(ClassFile("a/B").also {
            it.utf("Ljava/util/Map<Ljava/lang/String;Ljava/util/List<Ljava/io/File;>;>;") // a Signature attribute
            it.utf("LBad;")
            it.string("Ljava/net/Socket;") // a string constant, fail closed
            it.utf("Lowercase text with a semicolon")
            it.utf("Hello; Lorem ipsum;") // L is not at a start of a type here
            it.utf("HELLO;") // an L inside a word does not start a type
        })
        for (e in listOf("java/util/Map", "java/lang/String", "java/util/List", "java/io/File", "Bad", "java/net/Socket")) assertTrue("$e in $r", e in r)
        assertEquals(setOf("a/B", "java/lang/Object") + setOf("java/util/Map", "java/lang/String", "java/util/List", "java/io/File", "Bad", "java/net/Socket"), r.toSet())
    }

    @Test fun aStringConstantThatReadsAsAClassNameOnlyCountsWhenItIsNotAllowed() {
        val x = "helium314/keyboard/latin/vim/X"
        val bad = ClassFile(x).also { it.string("java/io/File") }
        assertEquals(listOf("$x has the constant java/io/File, which reads as a class name that is not on the vim allow list"), VimBytecode.check(mapOf("$x.class" to bad.bytes()), committed))
        // positive controls: a class name that is allowed, text that is no name, and a dotted name
        for (text in listOf("helium314/keyboard/latin/vim/VimBuf", "java/util/Locale", "hello world", "10 20 split", "java.io.File", "text", ""))
            assertEquals(text, emptyList<String>(), VimBytecode.check(mapOf("$x.class" to ClassFile(x).also { it.string(text) }.bytes()), committed))
        assertEquals(listOf("$x has the constant text/plain, which reads as a class name that is not on the vim allow list"), VimBytecode.check(mapOf("$x.class" to ClassFile(x).also { it.string("text/plain") }.bytes()), committed))
    }

    @Test fun onlyTheThreeIntrinsicsMembersThatTheCompilerEmitsAreAllowedAndOnlyWithTheirDescriptor() {
        val objectString = "(Ljava/lang/Object;Ljava/lang/String;)V"
        for ((name, desc) in listOf("areEqual" to "(Ljava/lang/Object;Ljava/lang/Object;)Z", "checkNotNullParameter" to objectString, "checkNotNullExpressionValue" to objectString))
            assertEquals(name, emptyList<String>(), verdict { method("kotlin/jvm/internal/Intrinsics", name, desc) })
        val refused = listOf(
            "checkNotNull" to objectString, "checkNotNull" to "(Ljava/lang/Object;)V", "checkNotNullParameter" to "(Ljava/lang/Object;Ljava/lang/String;I)V", "checkNotNullExpressionValue" to "(Ljava/lang/String;)V",
            "areEqual" to "(Ljava/lang/Double;Ljava/lang/Double;)Z", "throwIllegalState" to "(Ljava/lang/String;)V", "throwIllegalArgument" to "(Ljava/lang/String;)V",
            "throwUninitializedPropertyAccessException" to "(Ljava/lang/String;)V", "throwAssert" to "(Ljava/lang/String;)V", "checkHasClass" to "(Ljava/lang/String;)V",
            "checkExpressionValueIsNotNull" to objectString, "checkParameterIsNotNull" to objectString, "checkReturnedValueIsNotNull" to objectString, "throwNpe" to "(Ljava/lang/String;)V",
        )
        for ((name, desc) in refused) assertTrue("$name$desc must be refused", verdict { method("kotlin/jvm/internal/Intrinsics", name, desc) }.any { it.contains("kotlin/jvm/internal/Intrinsics.$name:$desc") })
    }

    @Test fun otherKotlinMembersThatThrowWithACallerMessageAreRefused() {
        val denied = mapOf<String, ClassFile.() -> Unit>(
            "check(false) { text }" to { method("kotlin/PreconditionsKt", "check", "(ZLkotlin/jvm/functions/Function0;)V") },
            "error(text)" to { method("kotlin/PreconditionsKt__PreconditionsKt", "error", "(Ljava/lang/Object;)Ljava/lang/Void;") },
            "requireNotNull(x) { text }" to { method("kotlin/PreconditionsKt__PreconditionsKt", "requireNotNull", "(Ljava/lang/Object;Lkotlin/jvm/functions/Function0;)Ljava/lang/Object;") },
            "an assertion helper" to { method("kotlin/PreconditionsKt__AssertionsKt", "assert", "(Z)V") },
            "TODO(text)" to { method("kotlin/StandardKt", "TODO", "(Ljava/lang/String;)Ljava/lang/Void;") },
            "TODO(text) of the part class" to { method("kotlin/StandardKt__StandardKt", "TODO", "(Ljava/lang/String;)Ljava/lang/Void;") },
            "NotImplementedError" to { method("kotlin/NotImplementedError", "<init>", "(Ljava/lang/String;)V") },
            "UninitializedPropertyAccessException" to { method("kotlin/UninitializedPropertyAccessException", "<init>", "(Ljava/lang/String;)V") },
            "KotlinNothingValueException" to { method("kotlin/KotlinNothingValueException", "<init>", "(Ljava/lang/String;)V") },
            "KotlinNullPointerException" to { method("kotlin/KotlinNullPointerException", "<init>", "(Ljava/lang/String;)V") },
            "NoWhenBranchMatchedException with a message" to { method("kotlin/NoWhenBranchMatchedException", "<init>", "(Ljava/lang/String;)V") },
            "stackTraceToString" to { method("kotlin/ExceptionsKt", "stackTraceToString", "(Ljava/lang/Throwable;)Ljava/lang/String;") },
            "the part class of ExceptionsKt" to { method("kotlin/ExceptionsKt__ExceptionsKt", "printStackTrace", "(Ljava/lang/Throwable;)V") },
        )
        for ((what, build) in denied) assertTrue("$what must be refused", verdict(build).isNotEmpty())
        // positive controls: the compiler's own exhaustive-when exception, and the plain library calls of the package
        assertEquals(emptyList<String>(), verdict { method("kotlin/NoWhenBranchMatchedException", "<init>", "()V") })
        assertEquals(emptyList<String>(), verdict { method("kotlin/ranges/RangesKt", "coerceIn", "(III)I"); method("kotlin/text/StringsKt", "repeat", "(Ljava/lang/CharSequence;I)Ljava/lang/String;") })
    }

    @Test fun theNamedKotlinThrowersStayRefusedEvenIfTheirTypesAreAllowedLater() {
        // TODO and the Throwable helpers are also refused today because Void and Throwable are not listed; the rule by member name must hold on its own
        val withTypes = committed + listOf(false to "java/lang/Void", false to "java/lang/Throwable")
        val cases = mapOf(
            "kotlin/StandardKt.TODO" to "(Ljava/lang/String;)Ljava/lang/Void;", "kotlin/StandardKt__StandardKt.TODO" to "(Ljava/lang/String;)Ljava/lang/Void;",
            "kotlin/ExceptionsKt.stackTraceToString" to "(Ljava/lang/Throwable;)Ljava/lang/String;", "kotlin/ExceptionsKt__ExceptionsKt.printStackTrace" to "(Ljava/lang/Throwable;)V",
        )
        for ((member, desc) in cases) {
            val owner = member.substringBefore('.')
            val bytes = ClassFile("${pkg}T").also { it.method(owner, member.substringAfter('.'), desc) }.bytes()
            assertTrue("$member must be refused by its own rule", VimBytecode.check(mapOf("${pkg}T.class" to bytes), withTypes).any { it.contains("refers to $member:$desc") })
        }
        // the positive control: with the two types allowed, a method that merely uses them is accepted
        val plain = ClassFile("${pkg}T").also { it.method("kotlin/text/StringsKt", "x", "(Ljava/lang/Throwable;)Ljava/lang/Void;") }.bytes()
        assertEquals(emptyList<String>(), VimBytecode.check(mapOf("${pkg}T.class" to plain), withTypes))
    }

    // ---- every class of the package, whatever produced it

    private fun tempDir(): File = java.nio.file.Files.createTempDirectory("vimbytecode").toFile().also { it.deleteOnExit() }

    private fun File.putClass(name: String, bytes: ByteArray = ClassFile(name).bytes()): File {
        File(this, "$name.class").apply { parentFile.mkdirs() }.writeBytes(bytes)
        return this
    }

    private fun jar(vararg entries: String): File {
        val f = File(tempDir(), "lib.jar")
        java.util.zip.ZipOutputStream(f.outputStream()).use { z -> for (e in entries) { z.putNextEntry(java.util.zip.ZipEntry(e)); z.write(ByteArray(4)); z.closeEntry() } }
        return f
    }

    private val pkg = "helium314/keyboard/latin/vim/"

    @Test fun aJavacClassOfThePackageIsCheckedLikeAKotlinClass() {
        // the rest of the app sits in the same outputs and is not this guard's business (it does I/O, which would be denied in the package)
        val kotlinOut = tempDir().putClass("${pkg}VimEngine").putClass("helium314/keyboard/latin/Other", ClassFile("helium314/keyboard/latin/Other").also { it.method("java/io/File", "delete", "()Z") }.bytes())
        val javacOut = tempDir().putClass("${pkg}VimSink", ClassFile("${pkg}VimSink").also { it.method("java/io/RandomAccessFile", "write", "([B)V") }.bytes())
        val got = VimBytecode.collect(listOf(kotlinOut, javacOut), listOf(kotlinOut, javacOut))
        assertEquals(emptyList<String>(), got.problems)
        assertEquals(2, got.classes.size)
        val problems = VimBytecode.check(got.classes, committed)
        assertEquals(listOf("${pkg}VimSink refers to java/io/RandomAccessFile, which is not on the vim allow list", "${pkg}VimSink refers to java/io/RandomAccessFile.write:([B)V, which is not on the vim allow list"), problems)
        // the control: without the javac output the same class is not seen, which is why the output is listed
        assertEquals(emptyList<String>(), VimBytecode.check(VimBytecode.collect(listOf(kotlinOut), listOf(kotlinOut)).classes, committed))
    }

    @Test fun twoRootsMayHoldTheSameClassNameAndBothAreKept() {
        val a = tempDir().putClass("${pkg}Same")
        val b = tempDir().putClass("${pkg}Same", ClassFile("${pkg}Same").also { it.method("java/io/File", "delete", "()Z") }.bytes())
        val got = VimBytecode.collect(listOf(a, b), emptyList())
        assertEquals(2, got.classes.size)
        assertEquals(2, VimBytecode.check(got.classes, committed).size)
    }

    @Test fun aClassOfThePackageInAnOutputThatIsNotScannedIsAProblem() {
        val scanned = tempDir().putClass("${pkg}VimEngine")
        val other = tempDir().putClass("${pkg}Hidden")
        val got = VimBytecode.collect(listOf(scanned), listOf(scanned, other))
        assertEquals(listOf("${pkg}Hidden.class is in ${other.path}, a compiled output that the vim guard does not scan"), got.problems)
        // the same directory reached by another path is the scanned one
        assertEquals(emptyList<String>(), VimBytecode.collect(listOf(scanned), listOf(File(scanned, "../${scanned.name}"))).problems)
    }

    @Test fun aClassOfThePackageInAJarIsAProblemAndOtherJarsAndClassesAreNot() {
        val scanned = tempDir().putClass("${pkg}VimEngine")
        val bad = jar("${pkg}Y.class", "other/Z.class", "META-INF/versions/9/${pkg}M.class")
        assertEquals(
            listOf("${pkg}Y.class is in ${bad.path}, a compiled output that the vim guard does not scan", "META-INF/versions/9/${pkg}M.class is in ${bad.path}, a compiled output that the vim guard does not scan"),
            VimBytecode.collect(listOf(scanned), listOf(scanned, bad)).problems)
        // positive controls: a jar and a directory without a class of the package, a resource named like one, a class of a sibling package, a directory that does not exist
        val clean = jar("helium314/keyboard/latin/Other.class", "helium314/keyboard/latin/vimlike/A.class", "${pkg}notes.txt", "META-INF/${pkg}x.class")
        val otherDir = tempDir().putClass("helium314/keyboard/latin/Other").putClass("helium314/keyboard/latin/vimlike/A")
        assertEquals(emptyList<String>(), VimBytecode.collect(listOf(scanned), listOf(scanned, clean, otherDir, File(otherDir, "missing"))).problems)
    }

    @Test fun anEmptyCollectionOfScannedOutputsIsAProblemForTheCheck() {
        assertEquals(1, VimBytecode.check(VimBytecode.collect(emptyList(), emptyList()).classes, committed).size)
    }

    @Test fun anIntrinsicsCallWithAMessageIsRefused() {
        val problems = verdict { method("kotlin/jvm/internal/Intrinsics", "checkNotNull", "(Ljava/lang/Object;Ljava/lang/String;)V") }
        assertTrue(problems.toString(), problems.any { it.contains("kotlin/jvm/internal/Intrinsics.checkNotNull") })
        val thrown = verdict { method("kotlin/jvm/internal/Intrinsics", "throwIllegalState", "(Ljava/lang/String;)V") }
        assertTrue(thrown.toString(), thrown.any { it.contains("kotlin/jvm/internal/Intrinsics.throwIllegalState") })
    }
}

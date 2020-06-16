package asm

import codegeneration.*
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import util.inputStream
import util.writeBytes
import java.nio.file.Path


fun readToClassNode(classFile: Path): ClassNode = classFile.inputStream().use { stream ->
    ClassNode().also { ClassReader(stream).accept(it, 0) }
}


fun ClassNode.toBytes(): ByteArray = ClassWriter(0).also { accept(it) }.toByteArray()
fun ClassNode.writeTo(path: Path) = path.writeBytes(toBytes())



private infix fun Int.opCode(code: Int): Boolean = (this and code) != 0

infix fun ClassNode.opCode(code: Int) = access opCode code


private val Int.static get() = opCode(Opcodes.ACC_STATIC)
private val Int.private get() = opCode(Opcodes.ACC_PRIVATE)
private val Int.protected get() = opCode(Opcodes.ACC_PROTECTED)
private val Int.public get() = opCode(Opcodes.ACC_PUBLIC)
private val Int.packagePrivate get() = !private && !protected && !public
private val Int.final get() = opCode(Opcodes.ACC_FINAL)
private val Int.visibility: Visibility
    get() = when {
        private -> Visibility.Private
        protected -> Visibility.Protected
        public -> Visibility.Public
        packagePrivate -> Visibility.Package
        else -> error("Access is unexpectedly not private, protected, public, or package private...")
    }

val MethodNode.isStatic get() = access.static
val MethodNode.isFinal get() = access.final
val MethodNode.isAbstract get() = access.opCode(Opcodes.ACC_ABSTRACT)
val MethodNode.visibility: Visibility get() = access.visibility

val FieldNode.isStatic get() = access.static
val FieldNode.visibility: Visibility get() = access.visibility
val FieldNode.isFinal: Boolean get() = access.final


val ClassNode.isInterface get() = opCode(Opcodes.ACC_INTERFACE)
val ClassNode.isAbstract get() = opCode(Opcodes.ACC_ABSTRACT)
val ClassNode.isEnum get() = opCode(Opcodes.ACC_ENUM)
val ClassNode.isAnnotation get() = opCode(Opcodes.ACC_ANNOTATION)
val ClassNode.isFinal get() = access.final
val ClassNode.visibility: ClassVisibility
    get() = with(access) {
        when {
            protected -> error("Class access is unexpectedly protected")
            private -> ClassVisibility.Private
            public -> ClassVisibility.Public
            packagePrivate -> ClassVisibility.Package
            else -> error("Access is unexpectedly not private, protected, public, or package private...")
        }
    }

val InnerClassNode.isStatic get() = access.opCode(Opcodes.ACC_STATIC)


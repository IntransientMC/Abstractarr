package abstractor

import codegeneration.ClassAccess
import codegeneration.ClassVariant
import codegeneration.Public
import codegeneration.Visibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import metautils.api.*
import metautils.codegeneration.asm.toAsmAccess
import metautils.signature.ClassGenericType
import metautils.signature.fromNameAndTypeArgs
import metautils.signature.toClassfileName
import metautils.signature.toTypeArgumentsOfNames
import metautils.util.*
import java.nio.file.Path

interface IAbstractionType {
    val isAbstracted: Boolean
    val addInInterface: Boolean
    val addInBaseclass: Boolean
}

enum class MemberAbstractionType : IAbstractionType {
    None,
    Interface,
    Baseclass,
    BaseclassAndInterface;

    override val isAbstracted get() = this != None
    override val addInInterface get() = this == Interface || this == BaseclassAndInterface
    override val addInBaseclass get() = this == Baseclass || this == BaseclassAndInterface
}

enum class ClassAbstractionType : IAbstractionType {
    None,
    Interface,
    BaseclassAndInterface;

    override val isAbstracted get() = this != None
    override val addInInterface get() = this == Interface || this == BaseclassAndInterface
    override val addInBaseclass get() = this == BaseclassAndInterface
}

data class TargetSelector(
    val classes: (ClassApi) -> ClassAbstractionType,
    val methods: (ClassApi, ClassApi.Method) -> MemberAbstractionType,
    val fields: (ClassApi, ClassApi.Field) -> MemberAbstractionType
) {
    companion object {
        val All = TargetSelector({
            // Non-static inner class baseclasses are not supported yet
            if (it.isInnerClass && !it.isStatic) ClassAbstractionType.Interface
            else ClassAbstractionType.BaseclassAndInterface
        },
            { _, _ -> MemberAbstractionType.BaseclassAndInterface },
            { _, _ -> MemberAbstractionType.BaseclassAndInterface }
        )
    }
}

data class AbstractionMetadata(
    // A string prefix for api packages
    val versionPackage: VersionPackage,
    // Libraries used in the abstracted jar
    val classPath: List<Path>,
    // Whether to produce an api that won't be usable for runtime, but is rather suited for compiling against in dev
    val fitToPublicApi: Boolean,
    val writeRawAsm: Boolean,
    // Classes/methods/fields that will be abstracted
    val selector: TargetSelector,
    val javadocs: JavaDocs
)
/** A list used in testing and production to know what interfaces/classes to attach to minecraft interface */
typealias AbstractionManifest = Map<String, AbstractedClassInfo>

val AbstractionManifestSerializer = MapSerializer(String.serializer(), AbstractedClassInfo.serializer())

@Serializable
data class AbstractedClassInfo(val apiClassName: String, /*val isThrowable: Boolean,*/ val newSignature: String)

class Abstractor /*private*/ constructor(
    private val classes: Collection<ClassApi>,
    private val classNamesToClasses: Map<QualifiedName, ClassApi>,
//    private val classRanks: Map<QualifiedName, Int>,
    private val index: ClasspathIndex,
    // Classes that won't be abstracted, but will have a stub api interface so they can be referenced from elsewhere
    private val stubClasses: Set<QualifiedName>
) {

    companion object {
        inline fun parse(
            mcJar: Path,
            metadata: AbstractionMetadata,
            crossinline usage: (Abstractor) -> Unit
        ): AbstractionManifest {
            val classes = ClassApi.readFromJar(mcJar) { path ->
                path.toString().let { it.startsWith("/net/minecraft/") || it.startsWith("/com/mojang/blaze3d") }
            }
            val classNamesToClasses = classes.flatMap { outerClass ->
                outerClass.allInnerClassesAndThis().map { it.name to it }
            }.toMap()

            // We need to add the access of api interfaces, base classes, and base api interfaces.
            // For other things in ClassEntry we just pass empty list in assumption they won't be needed.
            val additionalEntries = listAllGeneratedClasses(classes, metadata)


            return ClasspathIndex.index(metadata.classPath + listOf(mcJar), additionalEntries) { index ->
                val stubClasses = getReferencedClasses(classNamesToClasses.values, metadata.selector)
                usage(
                    Abstractor(
                        classes, classNamesToClasses, stubClasses = stubClasses, index = index
                    )
                )

                val classesWithApiInterfaces = classNamesToClasses.values.filter {
                    it.isPublicApi && (metadata.selector.classes(it).isAbstracted || it.name in stubClasses)
                }
                buildAbstractionManifest(classesWithApiInterfaces, metadata.versionPackage)
            }
        }
    }

    fun abstract(destDir: Path, metadata: AbstractionMetadata) {
        require(destDir.parent.exists()) { "The chosen destination path '$destDir' is not in any existing directory." }
        require(destDir.parent.isDirectory()) { "The parent of the chosen destination path '$destDir' is not a directory." }

        destDir.deleteRecursively()
        destDir.createDirectory()

        runBlocking {
            coroutineScope {
                for (classApi in classes) {
                    if (!classApi.isPublicApiAsOutermostMember) continue
                    launch(Dispatchers.IO) {
                        ClassAbstractor(metadata, index, classApi, classNamesToClasses, stubClasses)
                            .abstractClass(destPath = destDir)
                    }
                }
            }
        }

    }
}

@PublishedApi
internal fun getReferencedClasses(
    allClasses: Collection<ClassApi>,
    selected: TargetSelector
): Set<QualifiedName> {
    return allClasses.filter { selected.classes(it).isAbstracted }
        .flatMap { it.getAllReferencedClasses(selected) }.toSet()
}

//private data class ClassRank(val name: QualifiedName, val rank: Int?)

// Checks what classes the first class classes reference, then what they reference, then what they reference, etc
// The first class classes have a rank of 1, what they reference has a rank of 2, and everything rank 2 references
// that has not been referenced yet has a rank of 3, etc
// unreferenced classes have a rank of null
//fun rankClasses(
//    classes: Map<QualifiedName, ClassApi>,
//    firstClassClasses: (ClassApi) -> Boolean
//): Map<QualifiedName, Int> {
//    var currentRank = 1
//    val rankedClasses = mutableMapOf<QualifiedName, Int>()
//
//    var currentClassesToRank: List<QualifiedName> = classes.filter { firstClassClasses(it.value) }.keys.toList()
//    do {
//        currentClassesToRank.forEach { rankedClasses[it] = currentRank }
//
//        currentRank++
//        val nextClassesToRank = currentClassesToRank.flatMap { classes.getValue(it).getAllReferencedClasses() }
//            .filter { it.isMcClassName() && !rankedClasses.containsKey(it) && classes.getValue(it).isPublicApi }
//            .map { it }
//        currentClassesToRank = nextClassesToRank
//    } while (nextClassesToRank.isNotEmpty())
//
//    println("Total classes = ${classes.size}")
//    println("Included classes = ${rankedClasses.size}")
//    println("Directly referenced classes = ${rankedClasses.filter { it.value == 2 }.size}")
//
//    return rankedClasses
//}


@PublishedApi
internal fun buildAbstractionManifest(
    classesWithApiInterfaces: Collection<ClassApi>,
    version: VersionPackage
): AbstractionManifest = with(version) {
    classesWithApiInterfaces.map { mcClass ->
        val mcClassName = mcClass.name.toSlashQualifiedString()
        val apiClass = mcClass.name.toApiClass()
        val oldSignature = mcClass.getSignature()
        val insertedApiClass = ClassGenericType.fromNameAndTypeArgs(
            name = apiClass,
            typeArgs = allApiInterfaceTypeArguments(mcClass).toTypeArgumentsOfNames()
        )

        val newSignature = oldSignature.copy(superInterfaces = oldSignature.superInterfaces + insertedApiClass)
        mcClassName to AbstractedClassInfo(
            apiClassName = apiClass.toSlashQualifiedString(),
            newSignature = newSignature.toClassfileName()
        )
    }.toMap()
}

@PublishedApi
internal fun listAllGeneratedClasses(
    classes: Collection<ClassApi>,
    metadata: AbstractionMetadata
): Map<QualifiedName, ClassEntry> = with(metadata.versionPackage) {
    classes.flatMap { outerClass ->
        // This also includes package private and private stuff, because mojang sometimes exposes private classes
        // in public apis... thank you java
        outerClass.allInnerClassesAndThis().flatMap { mcClass ->
            val baseclass = mcClass.name.toBaseClass() to entryJustForAccess(
                baseClassAccess(origIsInterface = mcClass.isInterface),
                isStatic = mcClass.isStatic,
                visibility = mcClass.visibility
            )

            val apiInterface = mcClass.name.toApiClass() to entryJustForAccess(
                apiInterfaceAccess(metadata),
                isStatic = mcClass.isInnerClass,
                visibility = Visibility.Public
            )

            listOf(apiInterface, baseclass)
        }
    }.toMap()
}

private fun entryJustForAccess(access: ClassAccess, visibility: Visibility, isStatic: Boolean): ClassEntry {
    return ClassEntry(
        methods = mapOf(), superInterfaces = listOf(), superClass = null,
        access = access.toAsmAccess(visibility, isStatic),
        name = QualifiedName.Empty
    )
}


internal fun apiInterfaceAccess(metadata: AbstractionMetadata) = ClassAccess(
    isFinal = metadata.fitToPublicApi,
    variant = ClassVariant.Interface
)


internal fun baseClassAccess(origIsInterface: Boolean) = ClassAccess(
    isFinal = false,
    variant = if (origIsInterface) ClassVariant.Interface else ClassVariant.AbstractClass
)


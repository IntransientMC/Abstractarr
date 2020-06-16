package abstractor

import signature.GenericReturnType
import signature.TypeArgumentDeclaration
import signature.remap
import util.*

class VersionPackage(private val versionPackage: String) {
    private fun PackageName?.toApiPackageName() = versionPackage.prependToQualified(this ?: PackageName.Empty)
    private fun ShortClassName.toApiShortClassName() =
        ShortClassName(("I" + outerClass()).prependTo(innerClasses()))

    fun String.remapToApiClass(dotQualified: Boolean = false, dollarQualified: Boolean = true) =
        toQualifiedName(dotQualified, dollarQualified).toApiClass().toString(dotQualified, dollarQualified)

    fun QualifiedName.toApiClass(): QualifiedName = if (isMcClassName()) {
        QualifiedName(
            packageName = packageName.toApiPackageName(),
            shortName = shortName.toApiShortClassName()
        )
    } else this


    fun <T : GenericReturnType> T.remapToApiClass(): T = remap { it.toApiClass() }
    fun List<TypeArgumentDeclaration>.remapDeclToApiClasses() = map { typeArg ->
        typeArg.copy(
            classBound = typeArg.classBound?.remapToApiClass(),
            interfaceBounds = typeArg.interfaceBounds.map { it.remapToApiClass() })
    }

}

fun PackageName?.isMcPackage(): Boolean = this?.startsWith("net", "minecraft") == true
fun QualifiedName.isMcClassName(): Boolean = packageName.isMcPackage()

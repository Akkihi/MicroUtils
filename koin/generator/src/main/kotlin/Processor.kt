package dev.inmo.micro_utils.koin.generator

import com.google.devtools.ksp.KSTypeNotPresentException
import com.google.devtools.ksp.KSTypesNotPresentException
import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSFile
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import dev.inmo.micro_utils.koin.annotations.GenerateKoinDefinition
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.scope.Scope
import java.io.File
import kotlin.reflect.KClass

class Processor(
    private val codeGenerator: CodeGenerator
) : SymbolProcessor {
    private val definitionClassName = ClassName("org.koin.core.definition", "Definition")
    private val koinDefinitionClassName = ClassName("org.koin.core.definition", "KoinDefinition")

    @OptIn(KspExperimental::class)
    override fun process(resolver: Resolver): List<KSAnnotated> {
        resolver.getSymbolsWithAnnotation(
            GenerateKoinDefinition::class.qualifiedName!!
        ).filterIsInstance<KSFile>().forEach { ksFile ->
            FileSpec.builder(
                ksFile.packageName.asString(),
                "GeneratedDefinitions${ksFile.fileName.removeSuffix(".kt")}"
            ).apply {
                addFileComment(
                    """
                        THIS CODE HAVE BEEN GENERATED AUTOMATICALLY
                        TO REGENERATE IT JUST DELETE FILE
                        ORIGINAL FILE: ${ksFile.fileName}
                    """.trimIndent()
                )
                ksFile.getAnnotationsByType(GenerateKoinDefinition::class).forEach {
                    val type = runCatching {
                        it.type.asTypeName()
                    }.getOrElse { e ->
                        if (e is KSTypeNotPresentException) {
                            e.ksType.toClassName()
                        } else {
                            throw e
                        }
                    }
                    val targetType = runCatching {
                        type.parameterizedBy(*(it.typeArgs.takeIf { it.isNotEmpty() } ?.map { it.asTypeName() } ?.toTypedArray() ?: return@runCatching type))
                    }.getOrElse { e ->
                        when (e) {
                            is KSTypeNotPresentException -> e.ksType.toClassName()
                        }
                        if (e is KSTypesNotPresentException) {
                            type.parameterizedBy(*e.ksTypes.map { it.toTypeName() }.toTypedArray())
                        } else {
                            throw e
                        }
                    }.copy(
                        nullable = it.nullable
                    )
                    fun addGetterProperty(
                        receiver: KClass<*>
                    ) {
                        addProperty(
                            PropertySpec.builder(
                                it.name,
                                targetType,
                            ).apply {
                                addKdoc(
                                    """
                                        @return Definition by key "${it.name}"
                                    """.trimIndent()
                                )
                                getter(
                                    FunSpec.getterBuilder().apply {
                                        addCode(
                                            "return " + (if (it.nullable) {
                                                "getOrNull"
                                            } else {
                                                "get"
                                            }) + "(named(\"${it.name}\"))"
                                        )
                                    }.build()
                                )
                                receiver(receiver)
                            }.build()
                        )
                    }

                    addGetterProperty(Scope::class)
                    addGetterProperty(Koin::class)

                    if (it.generateSingle) {
                        addFunction(
                            FunSpec.builder("${it.name}Single").apply {
                                addKdoc(
                                    """
                                        Will register [definition] with [org.koin.core.module.Module.single] and key "${it.name}"
                                    """.trimIndent()
                                )
                                receiver(Module::class)
                                addParameter(
                                    ParameterSpec.builder(
                                        "createdAtStart",
                                        Boolean::class
                                    ).apply {
                                        defaultValue("false")
                                    }.build()
                                )
                                addParameter(
                                    ParameterSpec.builder(
                                        "definition",
                                        definitionClassName.parameterizedBy(targetType.copy(nullable = false))
                                    ).build()
                                )
                                returns(koinDefinitionClassName.parameterizedBy(targetType.copy(nullable = false)))
                                addCode(
                                    "return single(named(\"${it.name}\"), createdAtStart = createdAtStart, definition = definition)"
                                )
                            }.build()
                        )
                    }

                    if (it.generateFactory) {
                        addFunction(
                            FunSpec.builder("${it.name}Factory").apply {
                                addKdoc(
                                    """
                                        Will register [definition] with [org.koin.core.module.Module.factory] and key "${it.name}"
                                    """.trimIndent()
                                )
                                receiver(Module::class)
                                addParameter(
                                    ParameterSpec.builder(
                                        "definition",
                                        definitionClassName.parameterizedBy(targetType.copy(nullable = false))
                                    ).build()
                                )
                                returns(koinDefinitionClassName.parameterizedBy(targetType.copy(nullable = false)))
                                addCode(
                                    "return factory(named(\"${it.name}\"), definition = definition)"
                                )
                            }.build()
                        )
                    }
                    addImport("org.koin.core.qualifier", "named")
                }
            }.build().let {
                File(
                    File(ksFile.filePath).parent,
                    "GeneratedDefinitions${ksFile.fileName}"
                ).takeIf { !it.exists() } ?.apply {
                    parentFile.mkdirs()

                    writer().use { writer ->
                        it.writeTo(writer)
                    }
                }
            }
        }

        return emptyList()
    }
}

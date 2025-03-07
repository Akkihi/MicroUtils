import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.text.Normalizer

private val json = Json {
    ignoreUnknownKeys = true
}

private const val baseClassName = "IetfLanguageCode"
private const val unknownBaseClassName = "Unknown$baseClassName"
private const val baseClassSerializerName = "IetfLanguageCodeSerializer"
private const val baseClassSerializerAnnotationName = "@Serializable(${baseClassSerializerName}::class)"

@Serializable
private data class LanguageCode(
    @SerialName("alpha2")
    val tag: String,
    @SerialName("English")
    val title: String
)

fun String.adaptAsTitle() = if (first().isDigit()) {
    "L$this"
} else {
    this
}

fun String.normalized() = Normalizer.normalize(this, Normalizer.Form.NFD).replace(Regex("[^\\p{ASCII}]"), "")

@Serializable
private data class LanguageCodeWithTag(
    @SerialName("langType")
    val tag: String,
    @SerialName("lang")
    val withSubtag: String
) {
    val partWithoutTag: String
        get() {
            return withSubtag.substring(
                withSubtag.indexOf("-") + 1, withSubtag.length
            )
        }
    val middleTag
        get() = if (partWithoutTag.contains("-")) {
            partWithoutTag.substring(0, partWithoutTag.indexOf("-"))
        } else {
            null
        }
    val middleTagTitle
        get() = middleTag ?.adaptAsTitle() ?: partWithoutTag.adaptAsTitle()
    val subtag: String
        get() = middleTag ?: partWithoutTag
    val endTag
        get() = if (partWithoutTag.contains("-")) {
            partWithoutTag.substring(partWithoutTag.indexOf("-") + 1, partWithoutTag.length)
        } else {
            null
        }
    val endTagAsTitle
        get() = endTag ?.adaptAsTitle()
}

data class Tag(
    val title: String,
    val tag: String,
    val subtags: List<Tag>
)

private fun printLanguageCodeAndTags(
    tag: Tag,
    parent: Tag? = null,
    indents: String = "    "
): String = if (tag.subtags.isEmpty()) {
"""${indents}${baseClassSerializerAnnotationName}
${indents}object ${tag.title} : ${parent ?.title ?: baseClassName}() { override val code: String = "${tag.tag}"; override val withoutDialect: String get() = ${parent ?.title ?.let { "$it.code" } ?: "code"} }"""
} else {
"""
${indents}${baseClassSerializerAnnotationName}
${indents}sealed class ${tag.title} : ${parent ?.title ?: baseClassName}() {
${indents}    override val code: String = "${tag.tag}"
${indents}    override val withoutDialect: String
${indents}        get() = code

${tag.subtags.joinToString("\n") { printLanguageCodeAndTags(it, tag, "${indents}    ") }}

${indents}    ${baseClassSerializerAnnotationName}
${indents}    companion object : ${tag.title}()
${indents}}
"""
}

fun buildKtFileContent(tags: List<Tag>): String = """
import kotlinx.serialization.Serializable

/**
 * This class has been automatically generated using
 * https://github.com/InsanusMokrassar/MicroUtils/tree/master/language_codes/generator . This generator uses
 * https://datahub.io/core/language-codes/ files (base and tags) and create the whole hierarchy using it.
 */
${baseClassSerializerAnnotationName}
sealed class $baseClassName {
    abstract val code: String
    abstract val withoutDialect: String

${tags.joinToString("\n") { printLanguageCodeAndTags(it, indents = "    ") } }

    $baseClassSerializerAnnotationName
    data class $unknownBaseClassName (override val code: String) : $baseClassName() {
        override val withoutDialect: String = code.takeWhile { it != '-' }
    }

    override fun toString() = code
}
""".trimIndent()

fun createStringConverterCode(tags: List<Tag>): String {
    fun createDeserializeVariantForTag(
        tag: Tag,
        pretitle: String = baseClassName,
        indents: String = "        "
    ): String {
        val currentTitle = "$pretitle.${tag.title}"
        return """${indents}$currentTitle.code -> $currentTitle${if (tag.subtags.isNotEmpty()) tag.subtags.joinToString("\n", "\n") { createDeserializeVariantForTag(it, currentTitle, indents) } else ""}"""
    }

    return """fun String.as$baseClassName(): $baseClassName {
    return when (this) {
${tags.joinToString("\n") { createDeserializeVariantForTag(it) }}
        else -> $baseClassName.${unknownBaseClassName}(this)
    }
}
fun convertTo$baseClassName(code: String) = code.as$baseClassName()
fun $baseClassName(code: String) = code.as$baseClassName()
"""
}

fun createSerializerCode(tags: List<Tag>): String {
    return """import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object $baseClassSerializerName : KSerializer<$baseClassName> {
    override val descriptor = String.serializer().descriptor

    override fun deserialize(decoder: Decoder): $baseClassName {
        return $baseClassName(decoder.decodeString())
    }

    override fun serialize(encoder: Encoder, value: IetfLanguageCode) {
        encoder.encodeString(value.code)
    }
}
"""
}

suspend fun main(vararg args: String) {
    val outputFolder = args.firstOrNull() ?.let { File(it) }
    outputFolder ?.mkdirs()
    val ietfLanguageCodesLink = "https://datahub.io/core/language-codes/r/language-codes.json"
    val ietfLanguageCodesAdditionalTagsLink = "https://datahub.io/core/language-codes/r/ietf-language-tags.json"

    val client = HttpClient()

    val ietfLanguageCodes = json.decodeFromString(
        ListSerializer(LanguageCode.serializer()),
        client.get(ietfLanguageCodesLink).bodyAsText()
    ).map {
        it.copy(
            title = it.title
                .replace(Regex("[;,()-]"), "")
                .split(" ")
                .joinToString("") { "${it.first().uppercase()}${it.substring(1)}" }
        )
    }
    val ietfLanguageCodesWithTagsMap = json.decodeFromString(
        ListSerializer(LanguageCodeWithTag.serializer()),
        client.get(ietfLanguageCodesAdditionalTagsLink).bodyAsText()
    ).filter { it.withSubtag != it.tag }.groupBy { it.tag }

    val tags = ietfLanguageCodes.map {
        val unformattedSubtags = ietfLanguageCodesWithTagsMap[it.tag] ?: emptyList()
        val threeLevelTags = unformattedSubtags.filter { it.endTag != null }.groupBy { it.middleTag }
        val subtags = unformattedSubtags.mapNotNull {
            if (it.endTag == null) {
                val currentSubtags = (threeLevelTags[it.subtag] ?: emptyList()).map {
                    Tag(it.endTagAsTitle!!.normalized(), it.withSubtag, emptyList())
                }
                Tag(it.middleTagTitle.normalized(), it.withSubtag, currentSubtags)
            } else {
                null
            }
        }
        Tag(it.title.normalized(), it.tag, subtags)
    }

    File(outputFolder, "LanguageCodes.kt").apply {
        delete()
        createNewFile()
        writeText(buildKtFileContent(tags))
    }

    File(outputFolder, "StringToLanguageCodes.kt").apply {
        delete()
        createNewFile()
        writeText(createStringConverterCode(tags))
    }

    File(outputFolder, "$baseClassSerializerName.kt").apply {
        delete()
        createNewFile()
        writeText(createSerializerCode(tags))
    }
}

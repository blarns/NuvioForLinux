package com.nuvio.app.features.settings

import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.lang_bulgarian
import nuvio.composeapp.generated.resources.lang_czech
import nuvio.composeapp.generated.resources.lang_dutch
import nuvio.composeapp.generated.resources.lang_english
import nuvio.composeapp.generated.resources.lang_french
import nuvio.composeapp.generated.resources.lang_german
import nuvio.composeapp.generated.resources.lang_greek
import nuvio.composeapp.generated.resources.lang_hungarian
import nuvio.composeapp.generated.resources.lang_indonesian
import nuvio.composeapp.generated.resources.lang_italian
import nuvio.composeapp.generated.resources.lang_japanese
import nuvio.composeapp.generated.resources.lang_polish
import nuvio.composeapp.generated.resources.lang_portuguese_portugal
import nuvio.composeapp.generated.resources.lang_romanian
import nuvio.composeapp.generated.resources.lang_slovak
import nuvio.composeapp.generated.resources.lang_spanish
import nuvio.composeapp.generated.resources.lang_turkish
import nuvio.composeapp.generated.resources.lang_norwegian
import nuvio.composeapp.generated.resources.lang_vietnamese
import org.jetbrains.compose.resources.StringResource

enum class AppLanguage(
    val code: String,
    val labelRes: StringResource,
) {
    BULGARIAN("bg", Res.string.lang_bulgarian),
    CZECH("cs", Res.string.lang_czech),
    DUTCH("nl", Res.string.lang_dutch),
    ENGLISH("en", Res.string.lang_english),
    FRENCH("fr", Res.string.lang_french),
    GERMAN("de", Res.string.lang_german),
    GREEK("el", Res.string.lang_greek),
    HUNGARIAN("hu", Res.string.lang_hungarian),
    INDONESIAN("id", Res.string.lang_indonesian),
    ITALIAN("it", Res.string.lang_italian),
    JAPANESE("ja", Res.string.lang_japanese),
    POLISH("pl", Res.string.lang_polish),
    PORTUGUESE("pt", Res.string.lang_portuguese_portugal),
    ROMANIAN("ro", Res.string.lang_romanian),
    SLOVAK("sk", Res.string.lang_slovak),
    SPANISH("es", Res.string.lang_spanish),
    TURKISH("tr", Res.string.lang_turkish),
    NORWEGIAN("nb", Res.string.lang_norwegian),
    VIETNAMESE("vi", Res.string.lang_vietnamese),
    ;

    companion object {
        fun fromCode(code: String?): AppLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: ENGLISH
    }
}

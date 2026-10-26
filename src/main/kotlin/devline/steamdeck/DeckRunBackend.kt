package devline.steamdeck

import com.google.gson.JsonObject

/** A task override never rewrites the saved connection/profile settings. */
internal fun deckRunBackend(params: JsonObject, settings: JsonObject): String {
    val override = params["backend"]?.let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "Build backend must be a string." }
        it.asString
    }
    val backend = override ?: settings["backend"]?.asString?.takeIf(String::isNotBlank) ?: "javafx"
    require(backend in setOf("javafx", "desktop-web")) { "Unsupported build backend." }
    return backend
}

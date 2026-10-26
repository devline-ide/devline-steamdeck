package devline.steamdeck

import com.google.gson.Gson
import com.google.gson.JsonObject
import devline.extensions.IdeExtensionContext
import devline.extensions.IdeExtensionManifest
import devline.extensions.IdeExtensionProvider
import devline.extensions.IdeExtensionRuntime

class SteamDeckExtension : IdeExtensionProvider {
    override val manifest: IdeExtensionManifest = javaClass.getResourceAsStream("/steamdeck-extension.json")!!.reader(Charsets.UTF_8).use {
        val json = Gson().fromJson(it, JsonObject::class.java)
        Gson().fromJson(json.getAsJsonObject("extension"), IdeExtensionManifest::class.java)
    }
    override fun activate(context: IdeExtensionContext): IdeExtensionRuntime = SteamDeckRuntime(context)
}

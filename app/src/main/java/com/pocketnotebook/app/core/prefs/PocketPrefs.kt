package com.pocketnotebook.app.core.prefs

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/** Small typed wrapper around SharedPreferences — settings + app state. */
class PocketPrefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("pocket_prefs", Context.MODE_PRIVATE)

    val themeFlow = MutableStateFlow(themeMode)

    // --- onboarding ---------------------------------------------------------
    var onboardDone: Boolean
        get() = sp.getBoolean("onboard_done", false)
        set(v) = sp.edit().putBoolean("onboard_done", v).apply()

    // --- active model per slot ----------------------------------------------
    fun activeModelPath(slot: String): String? = sp.getString("active_model_$slot", null)
    fun setActiveModel(slot: String, path: String?) {
        if (path == null) sp.edit().remove("active_model_$slot").apply()
        else sp.edit().putString("active_model_$slot", path).apply()
    }

    // --- theme ---------------------------------------------------------------
    var themeMode: String // system | dark | light
        get() = sp.getString("theme_mode", "system") ?: "system"
        set(v) {
            sp.edit().putString("theme_mode", v).apply()
            themeFlow.value = v
        }

    // --- generation defaults ---------------------------------------------------
    var temperature: Float
        get() = sp.getFloat("gen_temp", 0.35f)
        set(v) = sp.edit().putFloat("gen_temp", v).apply()
    var contextLength: Int
        get() = sp.getInt("gen_ctx", 8192)
        set(v) = sp.edit().putInt("gen_ctx", v).apply()
    var maxTokens: Int
        get() = sp.getInt("gen_max_tokens", 640)
        set(v) = sp.edit().putInt("gen_max_tokens", v).apply()
    var threadCount: Int
        get() = sp.getInt("gen_threads", 0) // 0 = auto (big.little aware)
        set(v) = sp.edit().putInt("gen_threads", v).apply()

    /** KV-cache quantization for the chat model: 0 = F16, 4 = Q4_0, 8 = Q8_0. */
    var kvQuant: Int
        get() = sp.getInt("gen_kv_quant", 4)
        set(v) = sp.edit().putInt("gen_kv_quant", v).apply()
}

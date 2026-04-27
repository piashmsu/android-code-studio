/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
*/
package com.tom.rv2ide.artificial.secrets

import com.tom.rv2ide.preferences.internal.prefManager

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
*/

object ApiKey {

    // Resolve the global Application context lazily so tests / non-app callers
    // don't crash. Returns null if the IDEApplication singleton hasn't been
    // initialised yet, in which case we fall back to plain SharedPreferences.
    private fun appContext(): android.content.Context? = try {
        val cls = Class.forName("com.tom.rv2ide.app.IDEApplication")
        cls.getField("instance").get(null) as android.content.Context
    } catch (_: Throwable) { null }

    private fun readSecret(legacyKey: String, vaultName: String = legacyKey): String {
        val ctx = appContext()
        return if (ctx != null) EncryptedKeyVault.getOrMigrate(ctx, vaultName, legacyKey)
        else prefManager.getString(legacyKey, "")
    }

    private fun writeSecret(legacyKey: String, value: String, vaultName: String = legacyKey) {
        val ctx = appContext()
        if (ctx != null) {
            EncryptedKeyVault.put(ctx, vaultName, value)
            // Belt-and-braces: never leave a copy of the secret in plain prefs.
            prefManager.putString(legacyKey, "")
        } else {
            prefManager.putString(legacyKey, value)
        }
    }

    // Check if AI Agent is enabled
    fun isAIAgentEnabled(): Boolean {
        return prefManager.getBoolean("ai_agent_enabled", false)
    }

    // Gemini API Key
    fun getGeminiApiKey(): String {
        return readSecret("ai_agent_gemini_api_key")
    }

    fun setGeminiApiKey(key: String) = writeSecret("ai_agent_gemini_api_key", key)

    fun hasGeminiKey(): Boolean {
        val key = getGeminiApiKey()
        return key.isNotBlank() && key.length > 20
    }

    // OpenAI API Key
    fun getOpenAIApiKey(): String {
        return readSecret("ai_agent_openai_api_key")
    }

    fun setOpenAIApiKey(key: String) = writeSecret("ai_agent_openai_api_key", key)

    fun hasOpenAIKey(): Boolean {
        val key = getOpenAIApiKey()
        return key.isNotBlank() && key.length > 20
    }

    // Deepseek API Key
    fun getDeepseekApiKey(): String {
        return readSecret("ai_agent_deepseek_api_key")
    }

    fun setDeepseekApiKey(key: String) = writeSecret("ai_agent_deepseek_api_key", key)

    fun hasDeepseekKey(): Boolean {
        val key = getDeepseekApiKey()
        return key.isNotBlank() && key.length > 20
    }

    // Anthropic API Key
    fun getAnthropicApiKey(): String {
        return readSecret("ai_agent_anthropic_api_key")
    }

    fun setAnthropicApiKey(key: String) = writeSecret("ai_agent_anthropic_api_key", key)

    fun hasAnthropicKey(): Boolean {
        val key = getAnthropicApiKey()
        return key.isNotBlank() && key.length > 20
    }

    // Grok API Key
    fun getGrokApiKey(): String {
        return readSecret("ai_agent_grok_api_key")
    }

    fun setGrokApiKey(key: String) = writeSecret("ai_agent_grok_api_key", key)

    fun hasGrokKey(): Boolean {
        val key = getGrokApiKey()
        return key.isNotBlank() && key.length > 20
    }

    // OpenRouter API Key (unified gateway for many models)
    fun getOpenRouterApiKey(): String {
        return readSecret("ai_agent_openrouter_api_key")
    }

    fun setOpenRouterApiKey(key: String) = writeSecret("ai_agent_openrouter_api_key", key)

    fun hasOpenRouterKey(): Boolean {
        val key = getOpenRouterApiKey()
        // OpenRouter keys are typically prefixed with `sk-or-v1-` and are long.
        return key.isNotBlank() && key.length > 20
    }

    fun getOpenRouterCustomModel(): String {
        return prefManager.getString("ai_agent_openrouter_custom_model", "")
    }

    fun setOpenRouterCustomModel(model: String) {
        prefManager.putString("ai_agent_openrouter_custom_model", model)
    }

    // OpenAI-compatible (custom endpoint). Lets the user point the agent at any
    // service that speaks the OpenAI /v1/chat/completions schema (Ollama, Together,
    // Groq, DeepInfra, Mistral La Plateforme, self-hosted, ...).
    fun getOpenAICompatApiKey(): String {
        return readSecret("ai_agent_openaicompat_api_key")
    }

    fun setOpenAICompatApiKey(key: String) {
        writeSecret("ai_agent_openaicompat_api_key", key)
    }

    fun hasOpenAICompatKey(): Boolean {
        // Some local/self-hosted endpoints (Ollama) accept any non-empty key — even
        // a placeholder. Only require a non-blank value, not minimum length.
        return getOpenAICompatApiKey().isNotBlank()
    }

    fun getOpenAICompatBaseUrl(): String {
        return prefManager.getString("ai_agent_openaicompat_base_url", "")
    }

    fun setOpenAICompatBaseUrl(url: String) {
        prefManager.putString("ai_agent_openaicompat_base_url", url)
    }

    fun getOpenAICompatModel(): String {
        return prefManager.getString("ai_agent_openaicompat_model", "")
    }

    fun setOpenAICompatModel(model: String) {
        prefManager.putString("ai_agent_openaicompat_model", model)
    }
    
    // Legacy methods for backward compatibility
    @Deprecated("Use getGeminiApiKey() instead", ReplaceWith("getGeminiApiKey()"))
    fun getApiKey(): String {
        return getGeminiApiKey()
    }
    
    // Get list of available providers
    fun getAvailableProviders(): List<String> {
        val providers = mutableListOf<String>()
        if (hasGeminiKey()) providers.add("Gemini")
        if (hasOpenAIKey()) providers.add("OpenAI")
        if (hasDeepseekKey()) providers.add("Deepseek")
        if (hasAnthropicKey()) providers.add("Anthropic")
        if (hasGrokKey()) providers.add("Grok")
        if (hasOpenRouterKey()) providers.add("OpenRouter")
        if (hasOpenAICompatKey()) providers.add("OpenAI-compatible")
        return providers
    }
    
    // Get all API keys as a map
    fun getAllApiKeys(): Map<String, String> {
        return mapOf(
            "gemini" to getGeminiApiKey(),
            "openai" to getOpenAIApiKey(),
            "deepseek" to getDeepseekApiKey(),
            "anthropic" to getAnthropicApiKey(),
            "grok" to getGrokApiKey(),
            "openrouter" to getOpenRouterApiKey(),
            "openaicompat" to getOpenAICompatApiKey()
        ).filterValues { it.isNotBlank() }
    }
    
    // Check if any API key is configured
    fun hasAnyApiKey(): Boolean {
        return hasGeminiKey() || hasOpenAIKey() || hasDeepseekKey() || 
               hasAnthropicKey() || hasGrokKey() || hasOpenRouterKey() || hasOpenAICompatKey()
    }
}
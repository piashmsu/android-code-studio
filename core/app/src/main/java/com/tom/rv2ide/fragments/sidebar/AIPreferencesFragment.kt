package com.tom.rv2ide.fragments.sidebar

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import android.content.SharedPreferences
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.artificial.dialogs.ProviderSwitchDialog
import com.tom.rv2ide.artificial.secrets.ApiKey
import com.tom.rv2ide.managers.CodeCompletionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.tom.rv2ide.artificial.dialogs.LocalLLMConfigDialog

class AIPreferencesFragment(
    private val aiAgent: AIAgentManager,
    private val agents: Agents,
    private val codeCompletionManager: CodeCompletionManager?
) : Fragment() {

    private lateinit var providerDropdown: AutoCompleteTextView
    private lateinit var modelDropdown: AutoCompleteTextView
    private lateinit var autoSwitchToggle: MaterialSwitch
    private lateinit var codeCompletionToggle: MaterialSwitch
    private lateinit var currentProviderText: MaterialTextView
    private lateinit var currentModelText: MaterialTextView
    private lateinit var openRouterCustomGroup: View
    private lateinit var openRouterCustomEdit: TextInputEditText
    private lateinit var openRouterCustomSave: MaterialButton
    private lateinit var openAICompatGroup: View
    private lateinit var openAICompatBaseUrlEdit: TextInputEditText
    private lateinit var openAICompatApiKeyEdit: TextInputEditText
    private lateinit var openAICompatModelEdit: TextInputEditText
    private lateinit var openAICompatSave: MaterialButton
    private var streamingToggle: MaterialSwitch? = null
    private var diffPreviewToggle: MaterialSwitch? = null
    
    private val providerSwitchDialog by lazy { ProviderSwitchDialog(requireContext()) }
    
    private var completionStateMonitorJob: Job? = null
    private var isCompletionEnabled = true

    /**
     * Listen for `code_completion_enabled` changes via the system instead of polling every
     * 100 ms. The polling loop kept a coroutine + a SharedPreferences read alive at all
     * times this fragment was visible, which wasted both CPU and battery.
     */
    private val completionPrefListener =
        SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            if (key != "code_completion_enabled") return@OnSharedPreferenceChangeListener
            val enabled = sp.getBoolean(key, true)
            if (enabled == isCompletionEnabled) return@OnSharedPreferenceChangeListener
            isCompletionEnabled = enabled
            if (codeCompletionToggle.isChecked != enabled) {
                codeCompletionToggle.isChecked = enabled
            }
            lifecycleScope.launch { applyCompletionStateChange(enabled) }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_ai_preferences, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initializeViews(view)
        setupProviderDropdown()
        setupModelDropdown()
        setupToggles()
        setupBackupButtons()
        updateCurrentStatus()
        startCompletionStateMonitoring()
    }

    override fun onResume() {
        super.onResume()
        updateCurrentStatus()
        updateProviderDropdownSelection()
        updateModelDropdown()
        refreshOpenRouterCustomModelVisibility()
        refreshOpenAICompatVisibility()
        syncCodeCompletionToggle()
    }
    
    override fun onPause() {
        super.onPause()
        stopCompletionStateMonitoring()
    }

    private fun initializeViews(view: View) {
        providerDropdown = view.findViewById(R.id.providerDropdown)
        modelDropdown = view.findViewById(R.id.modelDropdown)
        autoSwitchToggle = view.findViewById(R.id.autoSwitchToggle)
        codeCompletionToggle = view.findViewById(R.id.codeCompletionToggle)
        currentProviderText = view.findViewById(R.id.currentProviderText)
        currentModelText = view.findViewById(R.id.currentModelText)
        openRouterCustomGroup = view.findViewById(R.id.openRouterCustomModelGroup)
        openRouterCustomEdit = view.findViewById(R.id.openRouterCustomModelEdit)
        openRouterCustomSave = view.findViewById(R.id.openRouterCustomModelSave)
        setupOpenRouterCustomModel()

        openAICompatGroup = view.findViewById(R.id.openAICompatGroup)
        openAICompatBaseUrlEdit = view.findViewById(R.id.openAICompatBaseUrlEdit)
        openAICompatApiKeyEdit = view.findViewById(R.id.openAICompatApiKeyEdit)
        openAICompatModelEdit = view.findViewById(R.id.openAICompatModelEdit)
        openAICompatSave = view.findViewById(R.id.openAICompatSave)
        setupOpenAICompat()

        streamingToggle = view.findViewById(R.id.streamingToggle)
        diffPreviewToggle = view.findViewById(R.id.diffPreviewToggle)
        setupStreamingAndDiffToggles()
    }

    private fun setupStreamingAndDiffToggles() {
        val sp = android.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
        streamingToggle?.isChecked = sp.getBoolean("ai_agent_streaming_enabled", true)
        streamingToggle?.setOnCheckedChangeListener { _, checked ->
            sp.edit().putBoolean("ai_agent_streaming_enabled", checked).apply()
            showSnackbar(if (checked) "Streaming enabled" else "Streaming disabled")
        }
        diffPreviewToggle?.isChecked = sp.getBoolean("ai_agent_diff_preview_enabled", false)
        diffPreviewToggle?.setOnCheckedChangeListener { _, checked ->
            sp.edit().putBoolean("ai_agent_diff_preview_enabled", checked).apply()
            showSnackbar(if (checked) "Diff preview enabled" else "Diff preview disabled")
        }

        val planningToggle = view?.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.planningModeToggle)
        val initiallyOn = sp.getBoolean("ai_agent_planning_mode_enabled", false)
        planningToggle?.isChecked = initiallyOn
        com.tom.rv2ide.artificial.rules.WritingRules.planningModeEnabled = initiallyOn
        planningToggle?.setOnCheckedChangeListener { _, checked ->
            sp.edit().putBoolean("ai_agent_planning_mode_enabled", checked).apply()
            com.tom.rv2ide.artificial.rules.WritingRules.planningModeEnabled = checked
            showSnackbar(if (checked) "Planning mode enabled" else "Planning mode disabled")
        }

        val crossProviderToggle = view?.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.crossProviderFallbackToggle)
        crossProviderToggle?.isChecked = sp.getBoolean("ai_agent_cross_provider_fallback_enabled", false)
        crossProviderToggle?.setOnCheckedChangeListener { _, checked ->
            sp.edit().putBoolean("ai_agent_cross_provider_fallback_enabled", checked).apply()
            showSnackbar(
                if (checked) "Cross-provider fallback enabled — will switch providers on quota / rate-limit errors"
                else "Cross-provider fallback disabled — will stay on the active provider"
            )
        }

        wireDailyCostCap()
    }

    private fun wireDailyCostCap() {
        val ctx = context ?: return
        val limitInput = view?.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.dailyCostLimitInput)
            ?: return
        val statusView = view?.findViewById<MaterialTextView>(R.id.dailyCostStatus)

        fun refreshStatus() {
            val today = com.tom.rv2ide.artificial.usage.DailyCostGuard.spentTodayUsd(ctx)
            val limit = com.tom.rv2ide.artificial.usage.DailyCostGuard.limitUsd(ctx)
            val limitText = if (limit > 0.0)
                "Cap: ${com.tom.rv2ide.artificial.usage.CostEstimator.fmtUsd(limit)}"
            else "Cap: unlimited (0)"
            statusView?.text = "$limitText  •  spent today: " +
                com.tom.rv2ide.artificial.usage.CostEstimator.fmtUsd(today)
        }

        val current = com.tom.rv2ide.artificial.usage.DailyCostGuard.limitUsd(ctx)
        if (current > 0.0) limitInput.setText(String.format(java.util.Locale.US, "%.2f", current))
        refreshStatus()

        limitInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                val v = limitInput.text?.toString()?.trim().orEmpty().toDoubleOrNull() ?: 0.0
                com.tom.rv2ide.artificial.usage.DailyCostGuard.setLimitUsd(ctx, v)
                refreshStatus()
                showSnackbar(
                    if (v > 0.0) "Daily cap set to ${com.tom.rv2ide.artificial.usage.CostEstimator.fmtUsd(v)}"
                    else "Daily cap removed (unlimited)"
                )
                true
            } else false
        }
        limitInput.setOnFocusChangeListener { _, focused ->
            if (!focused) {
                val v = limitInput.text?.toString()?.trim().orEmpty().toDoubleOrNull() ?: 0.0
                com.tom.rv2ide.artificial.usage.DailyCostGuard.setLimitUsd(ctx, v)
                refreshStatus()
            }
        }
    }

    private fun setupOpenAICompat() {
        openAICompatBaseUrlEdit.setText(ApiKey.getOpenAICompatBaseUrl())
        openAICompatApiKeyEdit.setText(ApiKey.getOpenAICompatApiKey())
        openAICompatModelEdit.setText(ApiKey.getOpenAICompatModel())

        view?.findViewById<MaterialButton>(R.id.openAICompatPresets)?.setOnClickListener {
            showOpenAICompatPresets()
        }

        view?.findViewById<MaterialButton>(R.id.openAICompatDetectOllama)?.setOnClickListener {
            detectOllamaAsync()
        }

        openAICompatSave.setOnClickListener {
            val baseUrl = openAICompatBaseUrlEdit.text?.toString()?.trim().orEmpty()
            val key = openAICompatApiKeyEdit.text?.toString()?.trim().orEmpty()
            val model = openAICompatModelEdit.text?.toString()?.trim().orEmpty()
            if (baseUrl.isBlank() || model.isBlank()) {
                showSnackbar("Base URL and Model id are required")
                return@setOnClickListener
            }
            if (!(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))) {
                showSnackbar("Base URL must start with http:// or https://")
                return@setOnClickListener
            }
            ApiKey.setOpenAICompatBaseUrl(baseUrl)
            ApiKey.setOpenAICompatApiKey(key)
            ApiKey.setOpenAICompatModel(model)
            agents.setProvider("openaicompat")
            agents.setAgent(model)
            aiAgent.reinitializeWithSelectedModel()
            updateCurrentStatus()
            updateModelDropdown()
            refreshOpenAICompatVisibility()
            showSnackbar("Saved OpenAI-compatible endpoint: $model")
        }
    }

    /**
     * Curated list of common OpenAI-compatible providers. Tapping one fills in
     * the Base URL field so the user only has to paste their key + a model id.
     * Profiles deliberately do NOT auto-set the api key — keys are user-private
     * and we want the user to make a deliberate choice about what to paste.
     */
    private val openAICompatPresets: List<Triple<String, String, String>> = listOf(
        Triple("Together AI", "https://api.together.xyz/v1", "e.g. mistralai/Mixtral-8x7B-Instruct-v0.1"),
        Triple("Groq (fast)", "https://api.groq.com/openai/v1", "e.g. llama-3.1-70b-versatile"),
        Triple("DeepInfra", "https://api.deepinfra.com/v1/openai", "e.g. meta-llama/Meta-Llama-3.1-70B-Instruct"),
        Triple("Fireworks", "https://api.fireworks.ai/inference/v1", "e.g. accounts/fireworks/models/llama-v3p1-70b-instruct"),
        Triple("Anyscale", "https://api.endpoints.anyscale.com/v1", "e.g. meta-llama/Llama-3-70b-chat-hf"),
        Triple("Mistral (la Plateforme)", "https://api.mistral.ai/v1", "e.g. mistral-large-latest"),
        Triple("Perplexity", "https://api.perplexity.ai", "e.g. llama-3.1-sonar-large-128k-online"),
        Triple("OpenAI (official)", "https://api.openai.com/v1", "e.g. gpt-4o-mini"),
        Triple("OpenRouter (legacy via compat)", "https://openrouter.ai/api/v1", "e.g. openai/gpt-4o-mini"),
        Triple("Ollama (local on emulator)", "http://10.0.2.2:11434/v1", "e.g. llama3.1:8b"),
        Triple("Ollama (local LAN)", "http://localhost:11434/v1", "e.g. llama3.1:8b"),
        Triple("vLLM (self-hosted)", "http://localhost:8000/v1", "e.g. meta-llama/Llama-3-8B-Instruct"),
    )

    /**
     * Probe a handful of common Ollama endpoints and, if one responds, offer
     * to select it. Runs on a background coroutine so the UI stays responsive.
     */
    private fun detectOllamaAsync() {
        showSnackbar("Probing Ollama on 10.0.2.2:11434 / localhost:11434 …")
        lifecycleScope.launch {
            val detection = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    com.tom.rv2ide.artificial.agents.openaicompat.OllamaDetector.probeBlocking()
                } catch (_: Throwable) { null }
            }
            if (detection == null) {
                showSnackbar("No local Ollama found. Make sure `ollama serve` is running.")
                return@launch
            }
            openAICompatBaseUrlEdit.setText(detection.baseUrl)
            val models = detection.models
            if (models.isNotEmpty()) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Ollama detected at ${detection.baseUrl}")
                    .setItems(models.toTypedArray()) { _, which ->
                        openAICompatModelEdit.setText(models[which])
                        showSnackbar("Model set to ${models[which]}. Tap Save endpoint to activate.")
                    }
                    .setNegativeButton("Close", null)
                    .show()
            } else {
                showSnackbar("Ollama detected at ${detection.baseUrl} but no models — run `ollama pull llama3.1`.")
            }
        }
    }

    private fun showOpenAICompatPresets() {
        val labels = openAICompatPresets.map { (name, url, hint) -> "$name\n$url\n$hint" }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Pick a preset")
            .setItems(labels) { _, which ->
                val (_, url, _) = openAICompatPresets[which]
                openAICompatBaseUrlEdit.setText(url)
                showSnackbar("Base URL set — paste your API key + model id, then Save endpoint.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshOpenAICompatVisibility() {
        val isCompat = agents.getProvider() == "openaicompat"
        openAICompatGroup.visibility = if (isCompat) View.VISIBLE else View.GONE
        if (isCompat) {
            val savedBase = ApiKey.getOpenAICompatBaseUrl()
            val savedKey = ApiKey.getOpenAICompatApiKey()
            val savedModel = ApiKey.getOpenAICompatModel()
            if (savedBase != openAICompatBaseUrlEdit.text?.toString())
                openAICompatBaseUrlEdit.setText(savedBase)
            if (savedKey != openAICompatApiKeyEdit.text?.toString())
                openAICompatApiKeyEdit.setText(savedKey)
            if (savedModel != openAICompatModelEdit.text?.toString())
                openAICompatModelEdit.setText(savedModel)
        }
    }

    private fun setupOpenRouterCustomModel() {
        openRouterCustomEdit.setText(ApiKey.getOpenRouterCustomModel())

        val saveAction = saveAction@{
            val typed = openRouterCustomEdit.text?.toString()?.trim().orEmpty()
            if (typed.isBlank()) {
                ApiKey.setOpenRouterCustomModel("")
                showSnackbar("Custom OpenRouter model cleared")
                return@saveAction
            }
            if (!typed.contains('/')) {
                showSnackbar("Model id must look like vendor/model (e.g. openai/gpt-4o-mini)")
                return@saveAction
            }
            ApiKey.setOpenRouterCustomModel(typed)
            agents.setProvider("openrouter")
            agents.setAgent(typed)
            aiAgent.reinitializeWithSelectedModel()
            updateCurrentStatus()
            updateModelDropdown()
            showSnackbar("Saved OpenRouter model: $typed")
        }

        openRouterCustomSave.setOnClickListener { saveAction() }
        openRouterCustomEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveAction()
                true
            } else false
        }
    }

    private fun refreshOpenRouterCustomModelVisibility() {
        val isOpenRouter = agents.getProvider() == "openrouter"
        openRouterCustomGroup.visibility = if (isOpenRouter) View.VISIBLE else View.GONE
        if (isOpenRouter) {
            val saved = ApiKey.getOpenRouterCustomModel()
            if (saved != openRouterCustomEdit.text?.toString()) {
                openRouterCustomEdit.setText(saved)
            }
        }
    }

    private fun setupProviderDropdown() {
        val providerMap = mapOf(
            "gemini" to "Google Gemini",
            "openai" to "OpenAI",
            "claude" to "Anthropic Claude",
            "deepseek" to "DeepSeek",
            "grok" to "xAI Grok",
            "openrouter" to "OpenRouter (multi-model)",
            "openaicompat" to "OpenAI-compatible (custom URL)",
            "localllm" to "Local LLM"
        )

        val allProviderIds = listOf("gemini", "openai", "claude", "deepseek", "grok", "openrouter", "openaicompat", "localllm")
        val providerNames = allProviderIds.map { providerMap[it] ?: it }
        
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, providerNames)
        providerDropdown.setAdapter(adapter)
        
        updateProviderDropdownSelection()
        
        providerDropdown.setOnItemClickListener { _, _, position, _ ->
            val selectedProviderId = allProviderIds[position]
            val selectedProviderName = providerNames[position]
            
            if (selectedProviderId == "localllm") {
                showLocalLLMConfigDialog(selectedProviderName)
            } else {
                handleProviderChange(selectedProviderId, selectedProviderName)
            }
        }
    }
    
    private fun showLocalLLMConfigDialog(providerName: String) {
        val dialog = LocalLLMConfigDialog { baseUrl, modelName ->
            handleProviderChange("localllm", providerName)
        }
        dialog.show(parentFragmentManager, "LocalLLMConfigDialog")
    }
    
    private fun updateProviderDropdownSelection() {
        val providerMap = mapOf(
            "gemini" to "Google Gemini",
            "openai" to "OpenAI",
            "claude" to "Anthropic Claude",
            "deepseek" to "DeepSeek",
            "grok" to "xAI Grok",
            "openrouter" to "OpenRouter (multi-model)",
            "openaicompat" to "OpenAI-compatible (custom URL)",
            "localllm" to "Local LLM"
        )
        
        val currentProviderId = agents.getProvider()
        val currentProviderName = providerMap[currentProviderId] ?: currentProviderId
        providerDropdown.setText(currentProviderName, false)
    }
    
    private fun updateCurrentStatus() {
        val currentProvider = agents.getProvider()
        val currentModel = agents.getAgent()
        
        android.util.Log.d("AIPreferences", "Current provider: $currentProvider, model: $currentModel")
        
        val providerDisplayName = when(currentProvider) {
            "gemini" -> "Google Gemini"
            "openai" -> "OpenAI"
            "claude" -> "Anthropic Claude"
            "deepseek" -> "DeepSeek"
            "grok" -> "xAI Grok"
            "openrouter" -> "OpenRouter (multi-model)"
            "openaicompat" -> "OpenAI-compatible (custom URL)"
            "localllm" -> "Local LLM"
            else -> currentProvider.uppercase()
        }
        
        currentProviderText.text = providerDisplayName
        currentModelText.text = currentModel
    }

    private fun setupModelDropdown() {
        updateModelDropdown()

        modelDropdown.setOnItemClickListener { _, _, position, _ ->
            val displayModels = buildDisplayModels(agents.getProvider())
            if (position in displayModels.indices) {
                handleModelChange(displayModels[position])
            }
        }

        modelDropdown.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                commitTypedModel()
            }
        }

        modelDropdown.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                commitTypedModel()
                modelDropdown.clearFocus()
                true
            } else {
                false
            }
        }
    }

    private fun commitTypedModel() {
        val typed = modelDropdown.text?.toString()?.trim().orEmpty()
        if (typed.isBlank() || typed == agents.getAgent()) return
        val provider = agents.getProvider()
        // Persist the typed id into the provider-specific custom slot too,
        // so reopening the screen restores the user's choice.
        when (provider) {
            "openrouter" -> ApiKey.setOpenRouterCustomModel(typed)
            "openaicompat" -> ApiKey.setOpenAICompatModel(typed)
        }
        handleModelChange(typed)
        updateModelDropdown()
        showSnackbar("Saved model: $typed")
    }

    private fun buildDisplayModels(providerId: String): List<String> {
        val base = agents.getModelsForProvider(providerId).toMutableList()
        val saved = agents.getAgent()
        // For openrouter, also surface the saved custom model so users can see/pick
        // their typed model id even if it isn't part of the curated list.
        if (providerId == "openrouter") {
            val custom = ApiKey.getOpenRouterCustomModel().trim()
            if (custom.isNotBlank() && custom !in base) base.add(0, custom)
        }
        if (providerId == "openaicompat") {
            val custom = ApiKey.getOpenAICompatModel().trim()
            if (custom.isNotBlank() && custom !in base) base.add(0, custom)
        }
        if (saved.isNotBlank() && saved !in base) base.add(0, saved)
        // Defensive dedupe: previously the same id could appear twice when the user
        // typed a model that was also part of the curated list (e.g. picking
        // `openai/gpt-4o-mini` from OpenRouter and saving it as a custom model).
        return base.distinct()
    }

    private fun updateModelDropdown() {
        val currentProvider = agents.getProvider()
        val displayModels = buildDisplayModels(currentProvider)

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, displayModels)
        modelDropdown.setAdapter(adapter)

        val currentModel = agents.getAgent()
        when {
            currentModel.isNotBlank() -> modelDropdown.setText(currentModel, false)
            displayModels.isNotEmpty() -> modelDropdown.setText(displayModels[0], false)
        }
    }

    private fun setupBackupButtons() {
        view?.findViewById<MaterialButton>(R.id.backupExportBtn)?.setOnClickListener {
            lifecycleScope.launch {
                try {
                    val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.tom.rv2ide.artificial.settings.SettingsBackup.exportToDownloads(requireContext())
                    }
                    showSnackbar("Saved: ${file.absolutePath}")
                } catch (e: Throwable) {
                    showSnackbar("Export failed: ${e.message}")
                }
            }
        }
        view?.findViewById<MaterialButton>(R.id.backupImportBtn)?.setOnClickListener {
            val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(android.content.Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain"))
            }
            try { importBackupLauncher.launch(intent) }
            catch (_: android.content.ActivityNotFoundException) {
                showSnackbar("No file picker available on this device.")
            }
        }
    }

    private val importBackupLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        lifecycleScope.launch {
            val outcome = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val body = requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader().readText()
                    } ?: return@withContext com.tom.rv2ide.artificial.settings.SettingsBackup.ImportResult(
                        0, "Could not read selected file.",
                    )
                    com.tom.rv2ide.artificial.settings.SettingsBackup.import(requireContext(), body)
                } catch (e: Throwable) {
                    com.tom.rv2ide.artificial.settings.SettingsBackup.ImportResult(0, e.message)
                }
            }
            if (outcome.error != null) {
                showSnackbar("Import failed: ${outcome.error}")
            } else {
                showSnackbar("Imported ${outcome.applied} settings. Restart AI if needed.")
                aiAgent.reinitializeWithSelectedModel()
                updateCurrentStatus()
                updateModelDropdown()
                refreshOpenAICompatVisibility()
                refreshOpenRouterCustomModelVisibility()
            }
        }
    }

    private fun setupToggles() {
        autoSwitchToggle.isChecked = providerSwitchDialog.isAutoSwitchEnabled()
        autoSwitchToggle.setOnCheckedChangeListener { _, isChecked ->
            providerSwitchDialog.setAutoSwitch(isChecked)
            val message = if (isChecked) {
                "Auto-switch enabled"
            } else {
                "Auto-switch disabled"
            }
            showSnackbar(message)
        }
        
        val savedState = requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
            .getBoolean("code_completion_enabled", true)
        isCompletionEnabled = savedState
        codeCompletionToggle.isChecked = savedState
        
        codeCompletionToggle.setOnCheckedChangeListener { _, isChecked ->
            android.util.Log.d("AIPreferences", "Toggle changed to: $isChecked")
            
            isCompletionEnabled = isChecked
            
            requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("code_completion_enabled", isChecked)
                .apply()
            
            lifecycleScope.launch {
                applyCompletionStateChange(isChecked)
            }
            
            val message = if (isChecked) {
                "✅ Code completion enabled"
            } else {
                "❌ Code completion disabled"
            }
            showSnackbar(message)
        }
    }
    
    private fun startCompletionStateMonitoring() {
        stopCompletionStateMonitoring()
        requireContext()
            .getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(completionPrefListener)
    }

    private fun stopCompletionStateMonitoring() {
        completionStateMonitorJob?.cancel()
        completionStateMonitorJob = null
        // Best-effort: context may already be detached during teardown.
        try {
            context?.getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
                ?.unregisterOnSharedPreferenceChangeListener(completionPrefListener)
        } catch (_: IllegalStateException) {
            // Fragment already detached.
        }
    }
    
    private suspend fun applyCompletionStateChange(enabled: Boolean) {
        android.util.Log.d("AIPreferences", "Applying completion state change: $enabled")
        
        if (enabled) {
            codeCompletionManager?.reattachToCurrentEditor()
            android.util.Log.d("AIPreferences", "Re-enabled code completion")
        } else {
            codeCompletionManager?.cleanup()
            android.util.Log.d("AIPreferences", "Disabled code completion")
        }
    }

    private fun syncCodeCompletionToggle() {
        val savedState = requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
            .getBoolean("code_completion_enabled", true)
        
        android.util.Log.d("AIPreferences", "Syncing toggle: saved=$savedState")
        
        isCompletionEnabled = savedState
        codeCompletionToggle.isChecked = savedState
    }

    private fun handleProviderChange(providerId: String, providerName: String) {
        android.util.Log.d("AIPreferences", "Switching to provider: $providerId")

        // CRITICAL: persist the provider choice BEFORE setting the agent. setAgent()
        // reads the current provider to decide whether free-form (vendor/model) ids
        // belong to OpenRouter or stay attached to the previously-selected provider —
        // doing this in the wrong order leaves stale ids attached to the old provider.
        agents.setProvider(providerId)

        val availableModels = agents.getModelsForProvider(providerId)
        android.util.Log.d("AIPreferences", "Available models for $providerId: ${availableModels.joinToString()}")

        when (providerId) {
            "openrouter" -> {
                val custom = ApiKey.getOpenRouterCustomModel().trim()
                when {
                    custom.isNotBlank() -> agents.setAgent(custom)
                    availableModels.isNotEmpty() -> agents.setAgent(availableModels[0])
                }
            }
            "openaicompat" -> {
                // Restore the user's saved OpenAI-compatible model id; do NOT carry
                // over a model name from a different provider (e.g. an OpenRouter
                // `vendor/model` slug that the upstream wouldn't recognise).
                val saved = ApiKey.getOpenAICompatModel().trim()
                if (saved.isNotBlank()) agents.setAgent(saved)
            }
            else -> {
                if (availableModels.isNotEmpty()) {
                    val defaultModel = availableModels[0]
                    agents.setAgent(defaultModel)
                    android.util.Log.d("AIPreferences", "Set default model: $defaultModel")
                }
            }
        }
        refreshOpenRouterCustomModelVisibility()
        refreshOpenAICompatVisibility()

        updateModelDropdown()
        
        if (aiAgent.setProvider(providerId)) {
            aiAgent.reinitializeWithSelectedModel()
            updateCurrentStatus()
            
            lifecycleScope.launch {
                if (isCompletionEnabled) {
                    delay(500)
                    codeCompletionManager?.reattachToCurrentEditor()
                    android.util.Log.d("AIPreferences", "Reattached completion after provider change")
                }
            }
            
            showSnackbar("Switched to $providerName")
        } else {
            showSnackbar("⚠️ No valid API key for $providerName")
        }
    }

    private fun handleModelChange(modelName: String) {
        android.util.Log.d("AIPreferences", "Switching to model: $modelName")
        agents.setAgent(modelName)
        aiAgent.reinitializeWithSelectedModel()
        updateCurrentStatus()
        
        lifecycleScope.launch {
            if (isCompletionEnabled) {
                delay(500)
                codeCompletionManager?.reattachToCurrentEditor()
                android.util.Log.d("AIPreferences", "Reattached completion after model change")
            }
        }
        
        showSnackbar("Model switched to: $modelName")
    }

    private fun showSnackbar(message: String) {
        view?.let {
            Snackbar.make(it, message, Snackbar.LENGTH_SHORT).show()
        }
    }
    
    override fun onDestroyView() {
        stopCompletionStateMonitoring()
        super.onDestroyView()
    }
}
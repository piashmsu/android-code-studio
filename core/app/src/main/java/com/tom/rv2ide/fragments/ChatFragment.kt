package com.tom.rv2ide.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import androidx.recyclerview.widget.RecyclerView
import android.widget.LinearLayout
import android.content.SharedPreferences
import com.tom.rv2ide.R
import com.tom.rv2ide.adapters.FileModificationAdapter
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.managers.CodeCompletionManager
import com.tom.rv2ide.handlers.AIRequestHandler
import com.tom.rv2ide.utils.ProjectHelper.getProjectRoot
import com.tom.rv2ide.activities.editor.EditorHandlerActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class ChatFragment(
    private val aiAgent: AIAgentManager
) : Fragment() {

    private lateinit var promptInput: TextInputEditText
    private lateinit var executeBtn: MaterialButton
    private lateinit var clearBtn: MaterialButton
    private lateinit var exportBtn: MaterialButton
    private lateinit var statusText: MaterialTextView
    private lateinit var summaryText: MaterialTextView
    private lateinit var progressIndicator: CircularProgressIndicator
    private lateinit var fileModificationList: RecyclerView
    private lateinit var summaryCard: LinearLayout
    private lateinit var fileModificationAdapter: FileModificationAdapter
    
    private lateinit var codeCompletionManager: CodeCompletionManager
    private lateinit var aiRequestHandler: AIRequestHandler
    
    private var typingJob: Job? = null
    private var fileMonitorJob: Job? = null
    private var completionStateMonitorJob: Job? = null
    private var lastMonitoredFile: File? = null
    private var isSettingUpCompletion = false
    
    private val userRootProject = getProjectRoot().absolutePath.toString()
    
    private val sharedPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == "code_completion_enabled") {
            val isEnabled = prefs.getBoolean(key, true)
            android.util.Log.d("ChatFragment", "Completion preference changed: $isEnabled")
            
            lifecycleScope.launch {
                handleCompletionStateChange(isEnabled)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_chat, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initializeViews(view)
        setupRecyclerView()
        setupManagers()
        setupListeners()
        loadProject()
        registerPreferenceListener()
        offerCrashAnalysis()
    }

    private fun offerCrashAnalysis() {
        val ctx = context ?: return
        if (!com.tom.rv2ide.artificial.usage.CrashStash.hasUnreadCrash(ctx)) return
        val trace = com.tom.rv2ide.artificial.usage.CrashStash.consume(ctx) ?: return
        val snippet = trace.lineSequence().take(12).joinToString("\n").take(1200)
        val current = promptInput.text?.toString().orEmpty()
        if (current.isBlank()) {
            promptInput.setText(
                buildString {
                    append("The app just crashed. Please analyse this stack trace, ")
                    append("explain the root cause, and propose a fix:\n\n```\n")
                    append(snippet)
                    append("\n```")
                }
            )
        }
        showSnackbar("Last-run crash detected — prompt prefilled")
    }
    
    override fun onResume() {
        super.onResume()
        startFileMonitoring()
        startCompletionStateMonitoring()
    }
    
    override fun onPause() {
        super.onPause()
        stopFileMonitoring()
        stopCompletionStateMonitoring()
    }

    private lateinit var copyResponseBtn: MaterialButton

    private fun initializeViews(view: View) {
        promptInput = view.findViewById(R.id.anyText)
        executeBtn = view.findViewById(R.id.executeBtn)
        clearBtn = view.findViewById(R.id.clearBtn)
        exportBtn = view.findViewById(R.id.exportBtn)
        statusText = view.findViewById(R.id.statusText)
        summaryText = view.findViewById(R.id.summaryText)
        progressIndicator = view.findViewById(R.id.progressIndicator)
        fileModificationList = view.findViewById(R.id.fileModificationList)
        summaryCard = view.findViewById(R.id.summaryCard)
        copyResponseBtn = view.findViewById(R.id.copyResponseBtn)
        copyResponseBtn.setOnClickListener {
            val ctx = requireContext()
            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            val text = statusText.text?.toString().orEmpty()
            cm.setPrimaryClip(android.content.ClipData.newPlainText("AI response", text))
            android.widget.Toast.makeText(ctx, "Copied", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupRecyclerView() {
        fileModificationAdapter = FileModificationAdapter()
        fileModificationList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = fileModificationAdapter
            isNestedScrollingEnabled = false
        }
        
        fileModificationAdapter.setOnItemClickListener { fileName ->
            openFileInEditor(fileName)
        }
    }

    private fun setupManagers() {
        codeCompletionManager = CodeCompletionManager.getInstance(
            requireContext(),
            lifecycleScope,
            aiAgent
        )
        
        aiRequestHandler = AIRequestHandler(
            lifecycleScope,
            aiAgent,
            statusText,
            summaryText,
            progressIndicator,
            executeBtn,
            fileModificationList,
            fileModificationAdapter,
            summaryCard,
            onFileOpen = { fileName ->
                openFileInEditor(fileName)
            },
            onTypeText = { text, delay -> typeText(text, delay) },
            getCurrentFile = { getCurrentFile() },
            refreshEditor = { refreshCurrentEditor() }
        )
    }

    private fun setupListeners() {
        executeBtn.setOnClickListener {
            val userRequest = promptInput.text.toString()
            
            if (userRequest.isBlank()) {
                showSnackbar("Please enter a request")
                return@setOnClickListener
            }
            
            codeCompletionManager.clearSuggestion()
            aiRequestHandler.execute(userRequest)
            // The attachment is consumed by the agent on the very next call;
            // clear our chip so the user knows the image has been sent and
            // won't be re-attached to the next, unrelated message.
            if (com.tom.rv2ide.artificial.multimodal.ImageAttachment.hasPending()) {
                com.tom.rv2ide.artificial.multimodal.ImageAttachment.clear()
                refreshImageChip()
            }
        }
    
        clearBtn.setOnClickListener {
            clearConversation()
        }

        exportBtn.setOnClickListener {
            exportConversation()
        }

        requireView().findViewById<MaterialButton>(R.id.voiceBtn)?.setOnClickListener {
            launchVoiceInput()
        }

        requireView().findViewById<MaterialButton>(R.id.imageAttachBtn)?.setOnClickListener {
            offerImageSourceChoice()
        }

        requireView().findViewById<MaterialButton>(R.id.debugCrashBtn)?.setOnClickListener {
            launchCrashDebug()
        }

        wireTemplates()
        refreshImageChip()
    }

    /**
     * Tap the bug button → ask the AI to root-cause the most recent crash.
     * Reads the persisted crash dump (and a tail of process-local logcat
     * when readable) and stuffs both into a structured prompt the agent can
     * act on. If there's nothing to debug we tell the user instead of
     * spending tokens on a useless request.
     */
    private fun launchCrashDebug() {
        val ctx = context ?: return
        val report = com.tom.rv2ide.artificial.debug.CrashDebugger.gather(ctx)
        if (report.crashTrace.isBlank() && report.logcatTail.isBlank()) {
            showSnackbar("No recent crash dump or logcat entries to debug.")
            return
        }
        val prompt = com.tom.rv2ide.artificial.debug.CrashDebugger.buildPrompt(report)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("Debug last crash with AI?")
            .setMessage(
                "Source: ${report.source}\n\n" +
                "Trace size: ${report.crashTrace.length} chars\n" +
                "Logcat tail: ${report.logcatTail.length} chars\n\n" +
                "The AI will analyse the crash and propose a fix. " +
                "This counts against your daily AI cost cap (if set)."
            )
            .setPositiveButton("Send") { _, _ ->
                promptInput.setText(prompt)
                promptInput.setSelection(prompt.length.coerceAtMost(0))
                executeBtn.performClick()
                com.tom.rv2ide.artificial.debug.CrashDebugger.clearCrash(ctx)
            }
            .setNeutralButton("Copy prompt") { _, _ ->
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Crash prompt", prompt))
                showSnackbar("Crash prompt copied")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Ask the user whether to attach from gallery or take a fresh photo. The
     * resulting image (or images, gallery supports multi-pick on Android 13+)
     * is downscaled, JPEG-compressed and base64-encoded, then queued on
     * [com.tom.rv2ide.artificial.multimodal.ImageAttachment]. The chip strip
     * below the prompt updates live with thumbnails.
     */
    private fun offerImageSourceChoice() {
        if (com.tom.rv2ide.artificial.multimodal.ImageAttachment.isFull()) {
            showSnackbar("Maximum images attached. Remove one first.")
            return
        }
        val ctx = context ?: return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("Attach image")
            .setItems(arrayOf("Pick from gallery (multi-select)", "Take a photo")) { _, which ->
                if (which == 0) launchGalleryPicker() else launchCameraCapture()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun launchGalleryPicker() {
        val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            imagePickerLauncher.launch(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            showSnackbar("No image picker app available.")
        }
    }

    private var pendingCameraOutputUri: android.net.Uri? = null

    private fun launchCameraCapture() {
        val ctx = context ?: return
        try {
            val cacheDir = java.io.File(ctx.cacheDir, "ai_camera").apply { mkdirs() }
            val photoFile = java.io.File.createTempFile("camera_", ".jpg", cacheDir)
            val authority = "${ctx.applicationContext.packageName}.fileprovider"
            val uri = try {
                androidx.core.content.FileProvider.getUriForFile(ctx, authority, photoFile)
            } catch (_: Throwable) {
                // Fallback to a content:// URI via MediaStore — works on most devices
                // even when the FileProvider authority isn't declared in the manifest.
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, photoFile.name)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                }
                ctx.contentResolver.insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values,
                ) ?: return
            }
            pendingCameraOutputUri = uri
            val intent = android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
                addFlags(android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            cameraCaptureLauncher.launch(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            showSnackbar("No camera app available.")
        } catch (e: Exception) {
            android.util.Log.e("ChatFragment", "Camera launch failed", e)
            showSnackbar("Could not open camera: ${e.message}")
        }
    }

    private val imagePickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        // Multi-select on Android 4.4+ via clipData; single-select via .data
        val uris: List<android.net.Uri> = data.clipData?.let { clip ->
            (0 until clip.itemCount).map { clip.getItemAt(it).uri }
        } ?: listOfNotNull(data.data)
        encodeAndAddImages(uris)
    }

    private val cameraCaptureLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        val uri = pendingCameraOutputUri ?: return@registerForActivityResult
        pendingCameraOutputUri = null
        encodeAndAddImages(listOf(uri))
    }

    private fun encodeAndAddImages(uris: List<android.net.Uri>) {
        val ctx = context ?: return
        if (uris.isEmpty()) return
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val Attach = com.tom.rv2ide.artificial.multimodal.ImageAttachment
            val accepted = mutableListOf<String>()
            val skipped = mutableListOf<String>()
            for (uri in uris) {
                if (Attach.isFull()) {
                    skipped.add("max ${Attach.maxImages()} reached")
                    break
                }
                val name = uri.lastPathSegment?.substringAfterLast('/').orEmpty().take(24)
                val encoded = Attach.encodeFromUri(ctx.contentResolver, uri)
                if (encoded == null) {
                    skipped.add(name.ifBlank { "image" })
                    continue
                }
                val kb = encoded.sizeBytes / 1024
                val label = if (name.isNotBlank()) "$name • ${kb}KB" else "Image • ${kb}KB"
                Attach.add(
                    com.tom.rv2ide.artificial.multimodal.ImageAttachment.Item(
                        encoded.dataUrl, label, encoded.thumbnail,
                    ),
                )
                accepted.add(label)
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                refreshImageChip()
                val msg = buildString {
                    if (accepted.isNotEmpty()) {
                        append("Attached ${accepted.size} image")
                        if (accepted.size > 1) append('s')
                    }
                    if (skipped.isNotEmpty()) {
                        if (isNotEmpty()) append(" · ")
                        append("skipped ${skipped.size}")
                    }
                    if (isEmpty()) append("Could not read image.")
                }
                showSnackbar(msg)
            }
        }
    }

    /**
     * Render the pending image attachments as a horizontal strip of thumbnail
     * chips above the prompt input. Tapping a chip removes that one image; a
     * trailing "Clear all" chip wipes the whole list.
     */
    private fun refreshImageChip() {
        val v = view ?: return
        val container = v.findViewById<LinearLayout>(R.id.imageAttachmentStrip)
        val chip = v.findViewById<MaterialTextView>(R.id.imageAttachmentChip)
        val items = com.tom.rv2ide.artificial.multimodal.ImageAttachment.all()
        if (container == null) {
            // Older layout — fall back to the single-line text chip.
            chip ?: return
            if (items.isEmpty()) {
                chip.visibility = View.GONE
            } else {
                chip.visibility = View.VISIBLE
                chip.text = "🖼  ${items.size} image(s) attached  ✕ tap to clear"
                chip.setOnClickListener {
                    com.tom.rv2ide.artificial.multimodal.ImageAttachment.clear()
                    refreshImageChip()
                    showSnackbar("Image attachments cleared")
                }
            }
            return
        }
        container.removeAllViews()
        if (items.isEmpty()) {
            container.visibility = View.GONE
            chip?.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        chip?.visibility = View.GONE
        val ctx = container.context
        val px = (ctx.resources.displayMetrics.density * 56).toInt()
        items.forEachIndexed { idx, item ->
            val frame = android.widget.FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(px, px).apply {
                    rightMargin = (ctx.resources.displayMetrics.density * 6).toInt()
                }
            }
            val thumb = android.widget.ImageView(ctx).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                layoutParams = android.widget.FrameLayout.LayoutParams(px, px)
                if (item.thumbnail != null) setImageBitmap(item.thumbnail)
                else setImageResource(android.R.drawable.ic_menu_gallery)
                contentDescription = item.label
            }
            val remove = MaterialTextView(ctx).apply {
                text = "×"
                textSize = 14f
                setPadding(8, 0, 8, 0)
                setBackgroundColor(0x99000000.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.END }
                setOnClickListener {
                    com.tom.rv2ide.artificial.multimodal.ImageAttachment.removeAt(idx)
                    refreshImageChip()
                }
            }
            frame.addView(thumb)
            frame.addView(remove)
            frame.setOnClickListener {
                showSnackbar(item.label)
            }
            container.addView(frame)
        }
        // Trailing clear-all chip
        if (items.size > 1) {
            val clearAll = MaterialTextView(ctx).apply {
                text = "Clear all"
                setPadding(16, 8, 16, 8)
                setBackgroundColor(0x33FF4444)
                setTextColor(0xFFFF4444.toInt())
                setOnClickListener {
                    com.tom.rv2ide.artificial.multimodal.ImageAttachment.clear()
                    refreshImageChip()
                    showSnackbar("Image attachments cleared")
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    px,
                ).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            }
            container.addView(clearAll)
        }
    }

    /**
     * Launch the system speech-to-text picker. The returned text is appended to
     * whatever the user already typed so they can dictate over multiple shots.
     * Supports any language the device has a recognition model for (including
     * Bengali + English on most devices).
     */
    private fun launchVoiceInput() {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Dictate your request")
            putExtra(
                android.speech.RecognizerIntent.EXTRA_LANGUAGE,
                java.util.Locale.getDefault().toLanguageTag(),
            )
        }
        try {
            voiceInputLauncher.launch(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            showSnackbar("Speech recognition is not available on this device.")
        }
    }

    private val voiceInputLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        val matches = result.data
            ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            ?: return@registerForActivityResult
        val spoken = matches.firstOrNull()?.trim().orEmpty()
        if (spoken.isBlank()) return@registerForActivityResult
        val existing = promptInput.text?.toString().orEmpty()
        val combined = if (existing.isBlank()) spoken else "$existing $spoken"
        promptInput.setText(combined)
        promptInput.setSelection(combined.length)
    }

    private fun wireTemplates() {
        val view = requireView()
        view.findViewById<MaterialButton>(R.id.tplCalculator)?.setOnClickListener {
            applyTemplate(
                "Build a complete Calculator app for Android in Kotlin with Material 3. " +
                    "Include +, -, *, /, decimals, percent, sign toggle, clear, and a result " +
                    "display. Use a single Activity with a constraint or grid layout. Wire all " +
                    "buttons in onCreate. Create or modify the necessary files."
            )
        }
        view.findViewById<MaterialButton>(R.id.tplTodo)?.setOnClickListener {
            applyTemplate(
                "Build a complete Todo app for Android in Kotlin with Material 3 and Room. " +
                    "Include adding, editing, deleting and marking tasks complete. Persist to " +
                    "Room. Use a RecyclerView with checkboxes. Create or modify all needed files."
            )
        }
        view.findViewById<MaterialButton>(R.id.tplChat)?.setOnClickListener {
            applyTemplate(
                "Build a simple offline Chat UI for Android in Kotlin with Material 3. " +
                    "Two-bubble RecyclerView (user/assistant), input field, send button, and " +
                    "scroll-to-bottom on send. Persist messages with Room. Create or modify all " +
                    "needed files."
            )
        }
        view.findViewById<MaterialButton>(R.id.tplLogin)?.setOnClickListener {
            applyTemplate(
                "Build a Login screen for Android in Kotlin with Material 3 — email + password " +
                    "fields with validation, show/hide password toggle, primary Sign In button, " +
                    "and a 'Forgot password?' link. Use TextInputLayout. Create or modify all " +
                    "needed files."
            )
        }
    }

    private fun applyTemplate(text: String) {
        promptInput.setText(text)
        promptInput.setSelection(text.length)
    }

    private fun exportConversation() {
        try {
            if (com.tom.rv2ide.artificial.usage.SessionLog.isEmpty()) {
                showSnackbar("Nothing to export yet")
                return
            }
            val md = com.tom.rv2ide.artificial.usage.SessionLog.toMarkdown()
            val json = com.tom.rv2ide.artificial.usage.SessionLog.toJson()
            val outDir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ), "AndroidCodeStudio").apply { mkdirs() }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val mdFile = java.io.File(outDir, "ai-chat-$stamp.md")
            val jsonFile = java.io.File(outDir, "ai-chat-$stamp.json")
            mdFile.writeText(md)
            jsonFile.writeText(json)
            showSnackbar("Exported to Downloads/AndroidCodeStudio")
        } catch (e: Throwable) {
            showSnackbar("Export failed: ${e.message}")
        }
    }
    
    private fun registerPreferenceListener() {
        val prefs = requireContext().getSharedPreferences("ai_preferences", android.content.Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(sharedPrefsListener)
    }
    
    private fun unregisterPreferenceListener() {
        val prefs = requireContext().getSharedPreferences("ai_preferences", android.content.Context.MODE_PRIVATE)
        prefs.unregisterOnSharedPreferenceChangeListener(sharedPrefsListener)
    }
    
    private suspend fun handleCompletionStateChange(enabled: Boolean) {
        android.util.Log.d("ChatFragment", "handleCompletionStateChange: $enabled")
        
        if (enabled) {
            delay(200)
            val editor = getCurrentEditor()
            val suggestionView = getCurrentSuggestionView()
            
            if (editor != null && suggestionView != null) {
                android.util.Log.d("ChatFragment", "Re-enabling completion for current file")
                setupCodeCompletionForCurrentFile()
            }
        } else {
            android.util.Log.d("ChatFragment", "Disabling completion")
            codeCompletionManager.cleanup()
        }
    }
    
    private fun startCompletionStateMonitoring() {
        stopCompletionStateMonitoring()
        
        completionStateMonitorJob = lifecycleScope.launch {
            var lastKnownState = requireContext().getSharedPreferences("ai_preferences", android.content.Context.MODE_PRIVATE)
                .getBoolean("code_completion_enabled", true)
            
            while (true) {
                delay(200)
                
                val currentState = requireContext().getSharedPreferences("ai_preferences", android.content.Context.MODE_PRIVATE)
                    .getBoolean("code_completion_enabled", true)
                
                if (currentState != lastKnownState) {
                    android.util.Log.d("ChatFragment", "State change detected in monitor: $lastKnownState -> $currentState")
                    lastKnownState = currentState
                    handleCompletionStateChange(currentState)
                }
            }
        }
    }
    
    private fun stopCompletionStateMonitoring() {
        completionStateMonitorJob?.cancel()
        completionStateMonitorJob = null
    }

    private fun loadProject() {
        lifecycleScope.launch {
            try {
                val success = aiAgent.setProjectRoot(userRootProject)
                
                if (success) {
                    statusText.text = "Project loaded successfully"
                    showSnackbar("✦ Project loaded: ${userRootProject.substringAfterLast("/")}")
                } else {
                    statusText.text = "Failed to load project"
                    showSnackbar("✗ Project not found or invalid path")
                }
            } catch (e: Exception) {
                statusText.text = "Error loading project"
                showSnackbar("Error: ${e.message}")
            }
        }
    }
    
    private fun startFileMonitoring() {
        stopFileMonitoring()
        
        fileMonitorJob = lifecycleScope.launch {
            while (true) {
                delay(500)
                
                if (isSettingUpCompletion) {
                    continue
                }
                
                val prefs = requireContext().getSharedPreferences("ai_preferences", android.content.Context.MODE_PRIVATE)
                val isEnabled = prefs.getBoolean("code_completion_enabled", true)
                
                if (!isEnabled) {
                    continue
                }
                
                val currentFile = getCurrentFile()
                
                if (currentFile != null && currentFile != lastMonitoredFile) {
                    android.util.Log.d("ChatFragment", "File changed detected: ${currentFile.name}")
                    lastMonitoredFile = currentFile
                    setupCodeCompletionForCurrentFile()
                }
            }
        }
    }
    
    private fun stopFileMonitoring() {
        fileMonitorJob?.cancel()
        fileMonitorJob = null
    }
    
    private fun setupCodeCompletionForCurrentFile() {
        if (isSettingUpCompletion) {
            android.util.Log.d("ChatFragment", "Already setting up, skipping")
            return
        }
        
        val prefs = requireContext().getSharedPreferences("ai_preferences", android.content.Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("code_completion_enabled", true)
        
        if (!isEnabled) {
            android.util.Log.d("ChatFragment", "Code completion is disabled, skipping setup")
            return
        }
        
        isSettingUpCompletion = true
        
        lifecycleScope.launch {
            delay(200)
            
            val editor = getCurrentEditor()
            val suggestionView = getCurrentSuggestionView()
            
            if (editor != null && suggestionView != null) {
                android.util.Log.d("ChatFragment", "Setting up code completion")
                codeCompletionManager.setup(
                    editor,
                    suggestionView,
                    onReady = {
                        android.util.Log.d("ChatFragment", "✦ Code completion ready!")
                        isSettingUpCompletion = false
                    },
                    onError = { e ->
                        android.util.Log.e("ChatFragment", "✗ Completion setup failed: ${e.message}", e)
                        isSettingUpCompletion = false
                    }
                )
            } else {
                android.util.Log.w("ChatFragment", "Editor or SuggestionView is null, cannot setup")
                isSettingUpCompletion = false
            }
        }
    }
    
    fun getCodeCompletionManager(): CodeCompletionManager {
        return codeCompletionManager
    }

    private fun openFileInEditor(fileName: String) {
        if (userRootProject.isBlank()) {
            showSnackbar("Project path not set")
            return
        }
        
        lifecycleScope.launch {
            try {
                val file = findFileInProject(File(userRootProject), fileName)
                if (file == null) {
                    showSnackbar("File not found: $fileName")
                    return@launch
                }
                
                val activity = requireActivity()
                if (activity is EditorHandlerActivity) {
                    activity.openFile(file)
                    showSnackbar("Opened: ${file.name}")
                    
                    lastMonitoredFile = file
                    delay(500)
                    setupCodeCompletionForCurrentFile()
                }
            } catch (e: Exception) {
                showSnackbar("Error opening file: ${e.message}")
            }
        }
    }
    
    private fun findFileInProject(projectRoot: File, fileName: String): File? {
        if (!projectRoot.exists() || !projectRoot.isDirectory) {
            return null
        }
        
        return projectRoot.walkTopDown().firstOrNull { 
            it.isFile && it.name == fileName 
        }
    }

    private fun typeText(text: String, delayMs: Long = 10L) {
        typingJob?.cancel()
        typingJob = lifecycleScope.launch {
            try {
                val editor = getCurrentEditor() ?: return@launch
                val lines = text.lines()
                val currentText = StringBuilder()
                
                for (line in lines) {
                    val words = line.split(" ")
                    for (i in words.indices) {
                        currentText.append(words[i])
                        if (i < words.size - 1) {
                            currentText.append(" ")
                        }
                        editor.setText(currentText.toString())
                        delay(delayMs)
                    }
                    currentText.append("\n")
                    editor.setText(currentText.toString())
                }
            } catch (e: Exception) {
            }
        }
    }

    fun clearConversation() {
        lifecycleScope.launch {
            try {
                typingJob?.cancel()
                codeCompletionManager.clearSuggestion()
                aiAgent.clearConversation()
                com.tom.rv2ide.artificial.usage.SessionLog.clear()

                promptInput.text?.clear()
                statusText.text = "Conversation cleared. Ready for new request."
                fileModificationList.visibility = View.GONE
                summaryCard.visibility = View.GONE
                fileModificationAdapter.clear()
                
                showSnackbar("Conversation cleared")
            } catch (e: Exception) {
                showSnackbar("Error clearing: ${e.message}")
            }
        }
    }

    private fun getCurrentEditor() = try {
        val activity = requireActivity()
        if (activity is EditorHandlerActivity) {
            activity.getCurrentEditor()?.editor
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    private fun getCurrentFile() = try {
        val activity = requireActivity()
        if (activity is EditorHandlerActivity) {
            activity.getCurrentEditor()?.file
        } else null
    } catch (e: Exception) {
        null
    }
    
    private fun getCurrentSuggestionView() = try {
        val activity = requireActivity()
        if (activity is EditorHandlerActivity) {
            activity.getCurrentEditor()?.suggestionView
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    private fun refreshCurrentEditor() {
        try {
            val activity = requireActivity()
            if (activity is EditorHandlerActivity) {
                val currentEditor = activity.getCurrentEditor()
                val file = currentEditor?.file
                val newContent = file?.readText()
                val editorText = currentEditor?.editor?.text

                if (editorText != null && newContent != null) {
                    editorText.replace(0, editorText.length, newContent)
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun showSnackbar(message: String) {
        val anchorView = activity?.findViewById<View>(android.R.id.content) 
            ?: view 
            ?: return
        
        Snackbar.make(anchorView, message, Snackbar.LENGTH_SHORT).show()
    }
    
    override fun onDestroyView() {
        typingJob?.cancel()
        fileMonitorJob?.cancel()
        completionStateMonitorJob?.cancel()
        aiRequestHandler.cancel()
        unregisterPreferenceListener()
        super.onDestroyView()
    }
}
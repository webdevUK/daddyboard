package com.gifboard

import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import androidx.preference.PreferenceManager
import com.facebook.drawee.backends.pipeline.Fresco
import java.io.File
import java.io.FileOutputStream
import android.webkit.WebView
import android.webkit.CookieManager

/**
 * Main InputMethodService for the GIF IME.
 * Provides GIF search with minimal on-screen keyboard.
 */
class GifBoardService : InputMethodService() {

    companion object {
        private const val TAG = "GifBoardService"
        private const val PREFETCH_THRESHOLD = 8
        private const val DOUBLE_TAP_DELAY_MS = 300L
    }

    enum class KeyboardMode {
        NORMAL,      // lowercase letters
        SHIFTED,     // uppercase for one character
        CAPS_LOCK,   // uppercase until toggled off
        SYMBOLS      // numbers and special characters
    }

    private lateinit var searchInput: EditText
    private lateinit var clearButton: ImageButton

    private lateinit var settingsButton: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var gifRecycler: RecyclerView
    private lateinit var rootView: View

    // History UI
    private lateinit var historyContainer: View
    private lateinit var historyRecycler: RecyclerView
    private lateinit var gifHistoryRecycler: RecyclerView
    private lateinit var tabSearches: TextView
    private lateinit var tabGifs: TextView
    private lateinit var clearAllButton: Button
    
    private lateinit var historyAdapter: SearchHistoryAdapter
    private lateinit var gifHistoryAdapter: GifHistoryAdapter
    private var isGifHistoryTab = false
    private lateinit var historyDb: SearchHistoryDbHelper

    // Keyboard mode state
    private var currentMode = KeyboardMode.NORMAL
    private var lastShiftTapTime = 0L

    // Key button references for updating display
    private val letterButtons = mutableMapOf<Int, Button>()
    private lateinit var shiftButton: ImageButton
    private lateinit var symbolsButton: Button
    private lateinit var keyboardContainer: View
    private lateinit var searchBarContainer: View
    private lateinit var searchOverlay: View
    private var isSearchBarActive = false

    private lateinit var adapter: GifAdapter
    private lateinit var gifProvider: GifProvider
    private lateinit var headlessWebView: WebView

    // Backspace repeat handling
    private val backspaceHandler = Handler(Looper.getMainLooper())
    private var backspaceRunnable: Runnable? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var currentQuery = ""
    private var currentPage = 0
    private var isLoadingPage = false
    private var hasMorePages = true
    private var searchJob: Job? = null

    // Vibration
    private var vibrator: Vibrator? = null
    private var vibrationStrength: String = "medium"

    override fun onCreate() {
        super.onCreate()
        GifImageLoader.initialize(this)
        historyDb = SearchHistoryDbHelper(this)

        // Initialize vibrator
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private fun performKeyHaptic() {
        if (vibrationStrength == "off") return
        vibrator?.let { v ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amplitude = when (vibrationStrength) {
                    "light" -> 30
                    "medium" -> 80
                    "strong" -> 150
                    else -> 80
                }
                v.vibrate(VibrationEffect.createOneShot(5, amplitude))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(5)
            }
        }
    }

    private fun performClickHaptic() {
        if (vibrationStrength == "off") return
        vibrator?.let { v ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amplitude = when (vibrationStrength) {
                    "light" -> 50
                    "medium" -> 120
                    "strong" -> 200
                    else -> 120
                }
                v.vibrate(VibrationEffect.createOneShot(10, amplitude))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(10)
            }
        }
    }

    private fun performHeavyHaptic() {
        if (vibrationStrength == "off") return
        vibrator?.let { v ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amplitude = when (vibrationStrength) {
                    "light" -> 80
                    "medium" -> 180
                    "strong" -> 255
                    else -> 180
                }
                v.vibrate(VibrationEffect.createOneShot(20, amplitude))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(20)
            }
        }
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)

        // Keyboard mode reset is handled by dynamic engine if needed
        // (Dynamic engine re-fetches layout on restart)
        if (::rootView.isInitialized) {
            setupKeyboard(rootView)
        }

        if (::gifRecycler.isInitialized) {
             val prefs = PreferenceManager.getDefaultSharedPreferences(this)
             val columns = prefs.getInt("gif_columns", 2).coerceAtMost(4)
             val layoutManager = gifRecycler.layoutManager as? StaggeredGridLayoutManager
             if (layoutManager != null && layoutManager.spanCount != columns) {
                 layoutManager.spanCount = columns
             }

              val livePreviews = prefs.getBoolean("live_previews", true)
              val insertLink = prefs.getBoolean("link_on_long_press", false)
              val brokenBehavior = prefs.getString("broken_gif_behavior", "hide") ?: "hide"
              adapter.setPreferences(livePreviews, insertLink, brokenBehavior)

              vibrationStrength = prefs.getString("vibration_strength", "medium") ?: "medium"

              if (::headlessWebView.isInitialized) {
                  updateGifProvider()
              }
         }
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.input_view, null)
        rootView = view

        headlessWebView = WebView(this).apply {
            visibility = View.GONE
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cookieManager.setAcceptThirdPartyCookies(this, true)
            }
            
            // Bypass Google's cookie consent banner while rejecting all tracking.
            // See GoogleConsentCookies for details on the two-cookie protocol.
            cookieManager.setCookie(".google.com", GoogleConsentCookies.buildConsentCookie())
            cookieManager.setCookie(".google.com", GoogleConsentCookies.buildSocsCookie())
        }
        (view as? ViewGroup)?.addView(headlessWebView)
        updateGifProvider()

        searchInput = view.findViewById(R.id.search_input)
        clearButton = view.findViewById(R.id.clear_button)

        settingsButton = view.findViewById(R.id.settings_button)
        progressBar = view.findViewById(R.id.progress_bar)
        gifRecycler = view.findViewById(R.id.gif_recycler)

        // History UI
        historyContainer = view.findViewById(R.id.history_container)
        historyRecycler = view.findViewById(R.id.history_recycler)
        gifHistoryRecycler = view.findViewById<RecyclerView>(R.id.gif_history_recycler)
        tabSearches = view.findViewById<TextView>(R.id.tab_searches)
        tabGifs = view.findViewById<TextView>(R.id.tab_gifs)
        clearAllButton = view.findViewById<Button>(R.id.clear_all_button)

        // Setup history adapter
        historyAdapter = SearchHistoryAdapter(
            onItemClick = { query ->
                performClickHaptic()
                searchInput.setText(query)
                performSearch(query)
            },
            onDismissClick = { query ->
                performClickHaptic()
                historyDb.removeSearch(query)
                historyAdapter.removeItem(query)
                updateHistoryVisibility()
            }
        )
        historyRecycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        historyRecycler.adapter = historyAdapter

        // Setup GIF history adapter
        gifHistoryAdapter = GifHistoryAdapter(
            onItemClick = { file ->
                performClickHaptic()
                // For local files, we don't have a web link, so pass null for linkUri
                // Do NOT pass file:// URI as it may cause crashes in some apps/InputContentInfo
                doCommitContent("GIF", "image/gif", file, null)
            },
            onDeleteClick = { file ->
                performKeyHaptic()
                try {
                    if (file.delete()) {
                        gifHistoryAdapter.removeFile(file)
                        if (gifHistoryAdapter.itemCount == 0) {
                           // If empty, maybe switch back or just show empty?
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete file", e)
                }
            }
        )
        // Same layout manager as main results
        gifHistoryRecycler.layoutManager = StaggeredGridLayoutManager(3, StaggeredGridLayoutManager.VERTICAL)
        gifHistoryRecycler.adapter = gifHistoryAdapter
        
        // Tab listeners
        tabSearches.setOnClickListener {
            performClickHaptic()
            isGifHistoryTab = false
            updateHistoryTabs()
        }
        
        tabGifs.setOnClickListener {
            performClickHaptic()
            isGifHistoryTab = true
            updateHistoryTabs()
        }

        // Hide keyboard when scrolling history
        historyRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if ((dy != 0 || dx != 0) && isSearchBarActive) {
                    deactivateSearchBar()
                }
            }
        })

        // Clear all button
        // Clear all button - context aware
        clearAllButton.setOnClickListener {
            performKeyHaptic()
            if (isGifHistoryTab) {
                // Clear GIF history files
                val imagesDir = File(cacheDir, "images")
                if (imagesDir.exists()) {
                     imagesDir.listFiles()?.forEach { it.delete() }
                }
                gifHistoryAdapter.clear()
            } else {
                // Clear search text history
                historyDb.clearAll()
                historyAdapter.clear()
            }
            // Stay in history view
        }

        // Setup GIF adapter
        adapter = GifAdapter(
            onGifClick = { url ->
                performClickHaptic()
                commitGif(url)
            },
            onGifLongClick = { url ->
                performHeavyHaptic()
                commitGifUrl(url)
            }
        )

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        vibrationStrength = prefs.getString("vibration_strength", "medium") ?: "medium"
        val columns = prefs.getInt("gif_columns", 2).coerceAtMost(4)
        val layoutManager = StaggeredGridLayoutManager(columns, StaggeredGridLayoutManager.VERTICAL)
        gifRecycler.layoutManager = layoutManager
        gifRecycler.adapter = adapter
        val livePreviews = prefs.getBoolean("live_previews", true)
        val insertLink = prefs.getBoolean("link_on_long_press", false)
        val brokenBehavior = prefs.getString("broken_gif_behavior", "hide") ?: "hide"
        adapter.setPreferences(livePreviews, insertLink, brokenBehavior)

        gifRecycler.setItemViewCacheSize(20)

        // Infinite scroll + hide keyboard on scroll
        gifRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                // Hide keyboard when scrolling
                if ((dy != 0 || dx != 0) && isSearchBarActive) {
                    deactivateSearchBar()
                }

                // Load more GIFs when near bottom
                if (dy > 0 && !isLoadingPage && hasMorePages) {
                    val totalItemCount = layoutManager.itemCount
                    val lastVisiblePositions = layoutManager.findLastVisibleItemPositions(null)
                    val lastVisiblePosition = lastVisiblePositions.maxOrNull() ?: 0

                    if (lastVisiblePosition >= totalItemCount - PREFETCH_THRESHOLD) {
                        loadMoreGifs()
                    }
                }
            }
        })

        // Search input text watcher - toggle history/GIF visibility
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                clearButton.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                updateHistoryVisibility()
            }
        })

        // Clear button
        clearButton.setOnClickListener {
            performKeyHaptic()
            searchInput.text.clear()
            adapter.clearGifs()
            currentQuery = ""
            updateHistoryVisibility()
        }



        // Settings button
        settingsButton.setOnClickListener {
            performKeyHaptic()
            val intent = Intent(this, SettingsActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }

        // Switch keyboard button (in search bar)
        view.findViewById<ImageButton>(R.id.switch_button)?.setOnClickListener {
            performKeyHaptic()
            // Try to switch to previous IME, if not available show IME picker
            if (!switchToPreviousInputMethod()) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showInputMethodPicker()
            }
        }

        // Wire up keyboard keys
        setupKeyboard(view)

        // Keyboard container and search bar
        keyboardContainer = view.findViewById(R.id.keyboard_container)
        searchBarContainer = view.findViewById(R.id.search_bar_container)
        searchOverlay = view.findViewById(R.id.search_overlay)

        // Tap on search bar to activate keyboard
        searchBarContainer.setOnClickListener {
            performKeyHaptic()
            activateSearchBar()
        }

        // Overlay click listener
        searchOverlay.setOnClickListener {
            performKeyHaptic()
            activateSearchBar()
        }

        // Ensure clicks on the non-clickable EditText also activate via parent,
        // but just in case focus changes directly:
        searchInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                activateSearchBar()
            } else {
                // If we lost focus but search is active, decide if we should keep it active?
                // For now, let deactivateSearchBar handle explicit deactivation.
            }
        }

        // Tap on GIF results area to deactivate search bar
        gifRecycler.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN && isSearchBarActive) {
                deactivateSearchBar()
            }
            false // Don't consume the event
        }

        // Initial history visibility
        updateHistoryVisibility()

        // Start with keyboard hidden
        deactivateSearchBar()

        return view
    }

    private fun activateSearchBar() {
        if (!isSearchBarActive) {
            isSearchBarActive = true
            updateKeyboardVisibility()

            // Hide overlay to allow interaction with EditText
            searchOverlay.visibility = View.GONE

            // Enable focus and request it
            searchInput.isFocusable = true
            searchInput.isFocusableInTouchMode = true
            searchInput.requestFocus()

            // Move cursor to end ONLY on initial activation
            searchInput.setSelection(searchInput.text.length)

            // Notify tutorial
            TutorialEventBus.emit(TutorialEvent.SearchBarActivated)
        } else {
            // Already active. User might be moving cursor, so DO NOT force it to end.
            // Just ensure we have focus (e.g. if they tapped the container but not the text)
            if (!searchInput.hasFocus()) {
                searchInput.requestFocus()
            }
        }
    }

    private fun deactivateSearchBar() {
        if (isSearchBarActive) {
            isSearchBarActive = false
            updateKeyboardVisibility()

            // Disable focus and clear it
            searchInput.clearFocus()
            searchInput.isFocusable = false
            searchInput.isFocusableInTouchMode = false

            // Show overlay to capture clicks
            searchOverlay.visibility = View.VISIBLE
        }
    }

    private fun updateKeyboardVisibility() {
        keyboardContainer.visibility = if (isSearchBarActive) View.VISIBLE else View.GONE
        searchBarContainer.isActivated = isSearchBarActive
    }

    private fun updateHistoryVisibility() {
        val isEmpty = searchInput.text.isNullOrEmpty()
        if (isEmpty) {
            historyContainer.visibility = View.VISIBLE
            gifRecycler.visibility = View.GONE
            updateHistoryTabs()
        } else {
            // Hide history, show GIF results
            historyContainer.visibility = View.GONE
            gifRecycler.visibility = View.VISIBLE
        }
    }

    private fun updateHistoryTabs() {
        if (isGifHistoryTab) {
            // Active: GIF History
            tabGifs.setTextColor(0xFF8AB4F8.toInt())
            tabGifs.typeface = android.graphics.Typeface.DEFAULT_BOLD
            
            tabSearches.setTextColor(0xFFAAAAAA.toInt())
            tabSearches.typeface = android.graphics.Typeface.DEFAULT

            historyRecycler.visibility = View.GONE
            gifHistoryRecycler.visibility = View.VISIBLE
            
            loadLocalGifHistory()
        } else {
            // Active: Search History
            tabSearches.setTextColor(0xFF8AB4F8.toInt())
            tabSearches.typeface = android.graphics.Typeface.DEFAULT_BOLD
            
            tabGifs.setTextColor(0xFFAAAAAA.toInt())
            tabGifs.typeface = android.graphics.Typeface.DEFAULT

            gifHistoryRecycler.visibility = View.GONE
            historyRecycler.visibility = View.VISIBLE
            
            // Load text history
            val history = historyDb.getHistory()
            historyAdapter.setHistory(history)
        }
    }

    private fun loadLocalGifHistory() {
        scope.launch(Dispatchers.IO) {
            val imagesDir = File(cacheDir, "images")
            val files = if (imagesDir.exists()) {
                imagesDir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".gif") }
                    ?.sortedByDescending { it.lastModified() }
                    ?: emptyList()
            } else {
                emptyList()
            }
            
            withContext(Dispatchers.Main) {
                gifHistoryAdapter.setFiles(files)
            }
        }
    }

    private fun setupKeyboard(view: View) {
        val container = view.findViewById<android.widget.LinearLayout>(R.id.dynamic_keyboard_container)
        container?.removeAllViews()

        var layoutStr = ""
        var bgColor = "#202020" // default dark background
        var keyColor = "#444444"
        val resolver = contentResolver
        try {
            val prefCursor = resolver.query(
                Uri.parse("content://helium314.keyboard.provider.layout/prefs"),
                null, null, null, null
            )
            prefCursor?.use { cursor ->
                val keyIdx = cursor.getColumnIndex("key")
                val valIdx = cursor.getColumnIndex("value")
                while (cursor.moveToNext()) {
                    if (keyIdx >= 0 && valIdx >= 0) {
                        val key = cursor.getString(keyIdx)
                        val value = cursor.getString(valIdx)
                        // If they are customizing colors, we could parse it, but standard heliboard themes
                        // are complex. For now, we'll try to extract standard theme variables or use defaults.
                        if (key.contains("background")) bgColor = value
                        if (key.contains("key_background")) keyColor = value
                    }
                }
            }

            val layoutCursor = resolver.query(
                Uri.parse("content://helium314.keyboard.provider.layout/json"),
                null, null, null, null
            )
            layoutCursor?.use { cursor ->
                if (cursor.moveToFirst()) {
                    layoutStr = cursor.getString(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query heliboard layout provider", e)
        }

        // Alphabet keys removed as requested by user.

        // Alphabet keys removed as requested by user.

        // Apply background
        try {
            val parsedBgColor = android.graphics.Color.parseColor(bgColor)
            rootView.setBackgroundColor(parsedBgColor)
            container?.setBackgroundColor(parsedBgColor)
            view.findViewById<View>(R.id.history_container)?.setBackgroundColor(parsedBgColor)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse background color: $bgColor", e)
        }

        fun createButton(label: String, weight: Float): Button {
            val b = Button(this)
            b.text = label
            b.isAllCaps = false
            b.layoutParams = android.widget.LinearLayout.LayoutParams(0, 150).apply {
                this.weight = weight
                setMargins(6, 6, 6, 6)
            }
            try { b.setBackgroundColor(android.graphics.Color.parseColor(keyColor)) } catch (e: Exception) { }
            b.setTextColor(android.graphics.Color.WHITE)
            return b
        }

        // Bottom row for functionality (switch back, space, clear, backspace)
        val bottomRow = android.widget.LinearLayout(this)
        bottomRow.orientation = android.widget.LinearLayout.HORIZONTAL
        bottomRow.layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        )

        val abcBtn = createButton("ABC", 1.5f)
        abcBtn.setOnClickListener {
            performKeyHaptic()
            switchInputMethod("helium314.keyboard/helium314.keyboard.latin.LatinIME")
        }
        bottomRow.addView(abcBtn)

        val spaceBtn = createButton(" ", 4f)
        spaceBtn.setOnClickListener {
            performKeyHaptic()
            val start = Math.max(searchInput.selectionStart, 0)
            val end = Math.max(searchInput.selectionEnd, 0)
            searchInput.text.replace(Math.min(start, end), Math.max(start, end), " ")
        }
        bottomRow.addView(spaceBtn)

        val clearBtn = createButton("CLEAR", 1.5f)
        clearBtn.setOnClickListener {
            performKeyHaptic()
            searchInput.text.clear()
        }
        bottomRow.addView(clearBtn)

        val delBtn = createButton("DEL", 1.5f)
        delBtn.setOnClickListener {
            performKeyHaptic()
            val text = searchInput.text
            if (text.isNotEmpty()) {
                val start = Math.max(searchInput.selectionStart, 0)
                val end = Math.max(searchInput.selectionEnd, 0)
                if (start != end) {
                    text.delete(Math.min(start, end), Math.max(start, end))
                } else if (start > 0) {
                    text.delete(start - 1, start)
                }
            }
        }
        // long press on DEL
        delBtn.setOnLongClickListener {
            searchInput.text.clear()
            true
        }
        bottomRow.addView(delBtn)

        container?.addView(bottomRow)
    }


    private fun performSearch(query: String) {
        if (query.isBlank()) return

        // Save to history
        historyDb.addSearch(query)

        // Hide keyboard after search
        deactivateSearchBar()

        searchJob?.cancel()
        currentQuery = query
        currentPage = 0
        hasMorePages = true

        clearSearchCaches()
        adapter.clearAndReset()
        progressBar.visibility = View.VISIBLE
        isLoadingPage = true

        // Notify tutorial
        TutorialEventBus.emit(TutorialEvent.SearchPerformed)

        searchJob = scope.launch(Dispatchers.Main) {
            try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(this@GifBoardService)
                val safeSearch = prefs.getString("safe_search", "active") ?: "active"
                val timeoutMs = prefs.getInt("search_timeout", 5) * 1000L

                val gifItems = gifProvider.search(query, 0, safeSearch, timeoutMs)

                progressBar.visibility = View.GONE
                isLoadingPage = false

                if (gifItems.isEmpty()) {
                    hasMorePages = false
                    adapter.setEndOfList(true)
                } else {
                    currentPage = 1
                    adapter.setGifs(gifItems)

                    // Auto-prefetch if content doesn't fill the view (can't scroll)
                    gifRecycler.post {
                        checkAndPrefetchIfNeeded()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Search failed", e)
                progressBar.visibility = View.GONE
                isLoadingPage = false
            }
        }
    }

    /**
     * Check if RecyclerView can scroll vertically. If not, auto-load more pages
     * until it can scroll or there are no more results.
     */
    private fun checkAndPrefetchIfNeeded() {
        if (!isLoadingPage && hasMorePages && !gifRecycler.canScrollVertically(1)) {
            loadMoreGifs()
        }
    }

    private fun loadMoreGifs() {
        if (currentQuery.isBlank() || isLoadingPage || !hasMorePages) return

        isLoadingPage = true
        adapter.setLoading(true)

        scope.launch(Dispatchers.Main) {
            try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(this@GifBoardService)
                val safeSearch = prefs.getString("safe_search", "active") ?: "active"
                val timeoutMs = prefs.getInt("search_timeout", 5) * 1000L

                val gifItems = gifProvider.search(currentQuery, currentPage, safeSearch, timeoutMs)

                adapter.setLoading(false)

                if (gifItems.isEmpty()) {
                    hasMorePages = false
                    adapter.setEndOfList(true)
                } else {
                    currentPage++
                    adapter.addGifs(gifItems)

                    // Continue prefetching if still not scrollable
                    gifRecycler.post {
                        checkAndPrefetchIfNeeded()
                    }
                }

                isLoadingPage = false
            } catch (e: Exception) {
                Log.e(TAG, "Load more failed", e)
                adapter.setLoading(false)
                isLoadingPage = false
            }
        }
    }

    private fun commitGif(contentUri: String) {
        val request = Request.Builder().url(contentUri).build()
        OkHttpClient().newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Log.e(TAG, "Failed to download GIF", e)
                window.window?.decorView?.post { handleDownloadFailure(contentUri) }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (!response.isSuccessful || response.body == null) {
                    Log.e(TAG, "Failed to download GIF: $response")
                    window.window?.decorView?.post { handleDownloadFailure(contentUri) }
                    return
                }

                val imagesDir = File(cacheDir, "images")
                if (!imagesDir.exists() && !imagesDir.mkdirs()) {
                    Log.e(TAG, "Failed to create images directory")
                    return
                }
                val file = File(imagesDir, "${System.currentTimeMillis()}.gif")

                try {
                    response.body?.byteStream()?.use { input ->
                        FileOutputStream(file).use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: java.io.IOException) {
                    Log.e(TAG, "Failed to save GIF", e)
                    window.window?.decorView?.post { handleDownloadFailure(contentUri) }
                    return
                }

                val linkUri = Uri.parse(contentUri)
                window.window?.decorView?.post { doCommitContent("GIF", "image/gif", file, linkUri) }
            }
        })
    }

    /**
     * Handle download failure based on user preference.
     * - If live previews disabled: always insert link (can't pre-detect broken GIFs)
     * - If behavior is "hide": don't insert anything
     * - If behavior is "overlay": insert link as fallback
     */
    private fun handleDownloadFailure(contentUri: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val livePreviews = prefs.getBoolean("live_previews", true)
        val brokenBehavior = prefs.getString("broken_gif_behavior", "hide") ?: "hide"
        
        if (!livePreviews) {
            // Live previews disabled - can't pre-detect broken GIFs, so insert link
            currentInputConnection?.commitText(contentUri, 1)
        } else if (brokenBehavior == "hide") {
            // User chose to hide broken GIFs - don't insert anything
            // (user was already warned by the overlay in the UI)
        } else {
            // "overlay" - insert link as fallback
            currentInputConnection?.commitText(contentUri, 1)
        }
    }

    private fun doCommitContent(description: String, mimeType: String, file: File, linkUri: Uri?) {
        val editorInfo = currentInputEditorInfo ?: return
        val contentUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

        val flag: Int
        if (Build.VERSION.SDK_INT >= 25) {
            flag = InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
        } else {
            flag = 0
            try {
                grantUriPermission(editorInfo.packageName, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                Log.e(TAG, "grantUriPermission failed", e)
            }
        }

        try {
            val inputContentInfo = InputContentInfoCompat(
                contentUri,
                ClipDescription(description, arrayOf(mimeType)),
                linkUri
            )

            var committed = false
            currentInputConnection?.let { ic ->
                committed = InputConnectionCompat.commitContent(ic, editorInfo, inputContentInfo, flag, null)
            }

            if (!committed && linkUri != null) {
                // App doesn't support GIF input - insert link as fallback
                currentInputConnection?.commitText(linkUri.toString(), 1)
            }
            switchToHeliboard()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to commit content", e)
        }
    }

    private fun commitGifUrl(url: String) {
        currentInputConnection?.commitText(url, 1)
        switchToHeliboard()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        clearSearchCaches()
    }

    private fun switchToHeliboard() {
        val heliboardId = "helium314.keyboard/helium314.keyboard.latin.LatinIME"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                switchInputMethod(heliboardId)
            } catch (e: Exception) {
                switchToPreviousInputMethod()
            }
        } else {
            val window = window.window ?: return
            val token = window.attributes.token
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.setInputMethod(token, heliboardId)
        }
    }

    private fun clearSearchCaches() {
        scope.launch(Dispatchers.IO) {
            // Clear Fresco image cache (memory + disk)
            try {
                Fresco.getImagePipeline().clearCaches()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear Fresco caches", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun updateGifProvider() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val providerKey = prefs.getString("gif_provider", "webview") ?: "webview"
        
        // Only update if the provider type has actually changed
        val currentProvider = if (::gifProvider.isInitialized) gifProvider else null
        
        when (providerKey) {
            "json_api" -> {
                if (currentProvider !is JsonApiGifProvider) {
                    gifProvider = JsonApiGifProvider()
                }
            }
            else -> {
                if (currentProvider !is GoogleGifFetcher) {
                    gifProvider = GoogleGifFetcher(headlessWebView)
                }
            }
        }
    }
}

package app.libre.ui.activities

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.SearchView
import androidx.constraintlayout.motion.widget.Key
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.allViews
import androidx.core.view.children
import androidx.core.view.isNotEmpty
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.onNavDestinationSelected
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.RecyclerView
import app.libre.BuildConfig
import app.libre.NavDirections
import app.libre.R
import app.libre.compat.PictureInPictureCompat
import app.libre.constants.IntentData
import app.libre.constants.PreferenceKeys
import app.libre.databinding.ActivityMainBinding
import app.libre.db.DatabaseHelper
import app.libre.db.obj.SearchHistoryItem
import app.libre.enums.ImportFormat
import app.libre.enums.SearchType
import app.libre.extensions.anyChildFocused
import app.libre.extensions.dpToPx
import app.libre.helpers.ImportHelper
import app.libre.helpers.IntentHelper
import app.libre.ui.dialogs.CreatePlaylistDialog
import app.libre.helpers.NavigationHelper
import app.libre.helpers.NetworkHelper
import app.libre.helpers.PreferenceHelper
import app.libre.helpers.ThemeHelper
import app.libre.helpers.UpdateHelper
import app.libre.parcelable.PlayerData
import app.libre.ui.dialogs.ErrorDialog
import app.libre.ui.dialogs.ImportTempPlaylistDialog
import app.libre.ui.fragments.AudioPlayerFragment
import app.libre.ui.fragments.PlayerFragment
import app.libre.ui.extensions.onSystemInsets
import app.libre.ui.models.PlaylistViewModel
import app.libre.ui.models.SearchViewModel
import app.libre.helpers.LocalAudioMatcher
import app.libre.util.TextUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import androidx.activity.OnBackPressedCallback
import androidx.activity.BackEventCompat
import app.libre.ui.models.CommonPlayerViewModel

class MainActivity : AbstractPlayerHostActivity() {
    private lateinit var binding: ActivityMainBinding

    lateinit var navController: NavController
    private var startFragmentId = R.id.libraryFragment

    // search related stuff
    private lateinit var searchView: SearchView
    lateinit var searchItem: MenuItem
    private var destinationChangedListener: NavController.OnDestinationChangedListener? = null
    private var savedSearchQuery: String? = null
    private var shouldOpenSuggestions = true
    private var currentSearchType: SearchType = SearchType.ONLINE
    private val searchViewModel: SearchViewModel by viewModels()
    private val playlistViewModel: PlaylistViewModel by viewModels()
    private val commonPlayerViewModel: CommonPlayerViewModel by viewModels()

    // registering for activity results is only possible, this here should have been part of
    // PlaylistOptionsBottomSheet instead if Android allowed us to
    private var playlistExportFormat: ImportFormat = ImportFormat.PIPED
    private var exportPlaylistId: String? = null
    private val createPlaylistsFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        if (uri == null) return@registerForActivityResult

        lifecycleScope.launch(Dispatchers.IO) {
            ImportHelper.exportPlaylists(
                this@MainActivity,
                uri,
                playlistExportFormat,
                selectedPlaylistIds = listOf(exportPlaylistId!!)
            )
        }
    }

    private val requestInitialPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val audioGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            results[android.Manifest.permission.READ_MEDIA_AUDIO] == true
        } else {
            results[android.Manifest.permission.READ_EXTERNAL_STORAGE] == true
        }
        if (audioGranted) {
            LocalAudioMatcher.startAutoScan(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.root.onSystemInsets { _, systemBarInsets ->
            binding.root.viewTreeObserver.addOnGlobalLayoutListener(object :
                ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    with(binding.appBarLayout) {
                        setPadding(
                            paddingLeft,
                            systemBarInsets.top,
                            paddingRight,
                            paddingBottom
                        )
                    }
                    binding.root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            })
        }

        // set the action bar for the activity
        setSupportActionBar(binding.toolbar)
        app.libre.helpers.LocalPlaylistsCache.initialize()

        val navHostFragment = binding.fragment.getFragment<NavHostFragment>()
        navController = navHostFragment.navController

        // save start tab fragment id
        startFragmentId = R.id.libraryFragment

        // set default tab as start fragment
        navController.graph = navController.navInflater.inflate(R.navigation.nav).also {
            it.setStartDestination(startFragmentId)
        }

        binding.toolbar.title = ThemeHelper.getStyledAppName(this)
        binding.toolbar.setOnClickListener {
            if (isPlayerExpanded()) return@setOnClickListener
            clearSearchViewFocus()
            if (this::searchItem.isInitialized && searchItem.isActionViewExpanded) {
                shouldOpenSuggestions = false
                searchItem.collapseActionView()
                shouldOpenSuggestions = true
            }
            val currentDest = navController.currentDestination?.id
            if (currentDest == R.id.searchResultFragment || currentDest == R.id.searchFragment) {
                navController.popBackStack(R.id.searchFragment, true)
            } else if (currentDest != R.id.libraryFragment) {
                navController.popBackStack(R.id.libraryFragment, false)
            }
        }

        // handle error logs
        PreferenceHelper.getErrorLog().ifBlank { null }?.let {
            if (!BuildConfig.DEBUG)
                ErrorDialog().show(supportFragmentManager, null)
        }

        setupSubscriptionsBadge()

        loadIntentData()

        lifecycleScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(4000L)
            UpdateHelper.cleanUpOldApks(this@MainActivity)
            UpdateHelper.checkForUpdateOnLaunch(this@MainActivity)
        }

        checkAndRequestInitialPermissions()
        setupUnifiedBackCoordinator()
        app.libre.helpers.WifiSyncHelper.start(applicationContext)
    }

    private fun checkAndRequestInitialPermissions() {
        val missingPermissions = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(android.Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (missingPermissions.isNotEmpty()) {
            requestInitialPermissions.launch(missingPermissions.toTypedArray())
        } else {
            LocalAudioMatcher.startAutoScan(this)
        }
    }

    /**
     * Try to find a scroll or recycler view and scroll it back to the top
     */
    private fun tryScrollToTop(view: View?) {
        val scrollView = view?.allViews
            ?.firstOrNull { it is ScrollView || it is NestedScrollView || it is RecyclerView }
        when (scrollView) {
            is ScrollView -> scrollView.smoothScrollTo(0, 0)
            is NestedScrollView -> scrollView.smoothScrollTo(0, 0)
            is RecyclerView -> scrollView.smoothScrollToPosition(0)
        }
    }

    /**
     * Initialize the notification badge showing the amount of new videos
     */
    private fun setupSubscriptionsBadge() {
        return
    }

    private fun isSearchInProgress(): Boolean {
        if (!this::navController.isInitialized) return false
        val id = navController.currentDestination?.id ?: return false

        return id in listOf(
            R.id.searchFragment,
            R.id.searchResultFragment
        )
    }

    private fun addSearchQueryToHistory(query: String) {
        val searchHistoryEnabled =
            PreferenceHelper.getBoolean("search_history_toggle", true)
        if (searchHistoryEnabled && query.isNotEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                val newItem = SearchHistoryItem(query.trim())
                DatabaseHelper.addToSearchHistory(newItem)
            }
        }
    }

    override fun invalidateMenu() {
        // Don't invalidate menu when in search in progress
        // this is a workaround as there is bug in android code
        // details of bug: https://issuetracker.google.com/issues/244336571
        if (isSearchInProgress()) {
            return
        }
        super.invalidateMenu()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return super.onOptionsItemSelected(item)
    }

    fun transitionToolbarColor(toColor: Int, durationMs: Long = 250L) {
        val currentDrawable = binding.appBarLayout.background
        val fromColor = if (currentDrawable is android.graphics.drawable.ColorDrawable) {
            currentDrawable.color
        } else {
            android.graphics.Color.TRANSPARENT
        }
        if (fromColor == toColor) return

        android.animation.ValueAnimator.ofArgb(fromColor, toColor).apply {
            duration = durationMs
            addUpdateListener { animator ->
                val color = animator.animatedValue as Int
                binding.appBarLayout.setBackgroundColor(color)
                binding.toolbar.setBackgroundColor(color)
            }
            start()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.action_bar, menu)

        // stuff for the search in the topBar
        val searchItem = menu.findItem(R.id.action_search)
        this.searchItem = searchItem
        searchView = searchItem.actionView as SearchView
        
        val searchAutoComplete = searchView.findViewById<androidx.appcompat.widget.SearchView.SearchAutoComplete>(androidx.appcompat.R.id.search_src_text)
        searchAutoComplete?.hint = getString(R.string.search_hint)
        searchAutoComplete?.textSize = 15f
        searchAutoComplete?.setPadding(12f.dpToPx(), 0, 8f.dpToPx(), 0)
        searchAutoComplete?.setTextColor(ThemeHelper.getThemeColor(this, com.google.android.material.R.attr.colorOnSurface))
        searchAutoComplete?.setHintTextColor(ThemeHelper.getThemeColor(this, com.google.android.material.R.attr.colorOutline))

        val searchPlate = searchView.findViewById<View>(androidx.appcompat.R.id.search_plate)
        searchPlate?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        val submitArea = searchView.findViewById<View>(androidx.appcompat.R.id.submit_area)
        submitArea?.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        searchView.background = AppCompatResources.getDrawable(this, R.drawable.search_bar_bg)
        
        val searchCloseButton = searchView.findViewById<android.widget.ImageView>(androidx.appcompat.R.id.search_close_btn)
        searchCloseButton?.setImageResource(R.drawable.ic_close)
        searchCloseButton?.imageTintList = android.content.res.ColorStateList.valueOf(
            ThemeHelper.getThemeColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
        )
        searchCloseButton?.setPadding(6f.dpToPx(), 6f.dpToPx(), 6f.dpToPx(), 6f.dpToPx())
        
        val innerSearchIcon = searchView.findViewById<android.widget.ImageView>(androidx.appcompat.R.id.search_mag_icon)
        innerSearchIcon?.setImageDrawable(null)
        innerSearchIcon?.visibility = View.GONE

        // automatically set a different search icon in the playlists
        destinationChangedListener?.let { navController.removeOnDestinationChangedListener(it) }
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            currentSearchType = when (destination.id) {
                R.id.playlistFragment -> SearchType.PLAYLIST
                else -> SearchType.ONLINE
            }
            // clear query in unused page so that they're reset when visiting the page the next time
            if (currentSearchType != SearchType.PLAYLIST) playlistViewModel.setQuery(null)

            val searchIconResource = when (currentSearchType) {
                SearchType.PLAYLIST -> R.drawable.ic_playlist_search
                SearchType.ONLINE -> R.drawable.ic_search
            }

            searchItem.setIcon(searchIconResource)
            
            if (isSearchInProgress()) {
                searchItem.isVisible = true
                if (!searchItem.isActionViewExpanded) {
                    shouldOpenSuggestions = false
                    searchItem.expandActionView()
                    shouldOpenSuggestions = true
                    if (destination.id == R.id.searchResultFragment) {
                        searchView.post {
                            searchView.clearFocus()
                            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                            imm.hideSoftInputFromWindow(searchView.windowToken, 0)
                        }
                    }
                }
            } else {
                searchItem.isVisible = currentSearchType == SearchType.PLAYLIST
                if (searchItem.isActionViewExpanded) {
                    shouldOpenSuggestions = false
                    searchItem.collapseActionView()
                    shouldOpenSuggestions = true
                }
            }

            val isLibraryScreen = destination.id == R.id.libraryFragment
            val isTopLevel = destination.id == R.id.libraryFragment

            if (isTopLevel) {
                binding.appBarLayout.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                binding.toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                binding.toolbar.title = ThemeHelper.getStyledAppName(this@MainActivity)
                binding.toolbar.navigationIcon = null
            } else {
                binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
                binding.toolbar.setNavigationIconTint(ThemeHelper.getThemeColor(this@MainActivity, com.google.android.material.R.attr.colorOnSurface))
                binding.toolbar.setNavigationOnClickListener {
                    navController.navigateUp()
                }
                if (destination.id == R.id.playlistFragment) {
                    binding.toolbar.title = ""
                } else {
                    binding.appBarLayout.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    binding.toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            }

            menu.findItem(R.id.action_settings)?.isVisible = isLibraryScreen
        }
        destinationChangedListener = listener
        navController.addOnDestinationChangedListener(listener)

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                searchView.clearFocus()

                // playlist and download search don't do anything on submit
                // as they search while typing
                if (currentSearchType != SearchType.ONLINE) return true

                // handle inserted YouTube-like URLs and directly open the referenced
                // channel, playlist or video instead of showing search results
                if (query.toHttpUrlOrNull() != null) {
                    val queryIntent = IntentHelper.resolveType(query.toUri())

                    val didNavigate = navigateToMediaByIntent(queryIntent) {
                        navController.popBackStack(R.id.searchFragment, true)
                        searchItem.collapseActionView()
                    }
                    if (didNavigate) return true
                }

                navController.navigate(NavDirections.showSearchResults(query))

                addSearchQueryToHistory(query)

                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                if (currentSearchType == SearchType.PLAYLIST) {
                    playlistViewModel.setQuery(newText)
                    return true
                }

                if (!shouldOpenSuggestions) return true

                // Prevent navigation when search view is collapsed
                if (searchView.isIconified) {
                    return true
                }

                // prevent malicious navigation when the search view is getting collapsed
                // Note: playlistFragment is excluded because it uses in-place filtering via playlistViewModel
                val destIds = listOf(
                    R.id.searchResultFragment,
                    R.id.channelFragment
                )
                if (navController.currentDestination?.id in destIds) {
                    return false
                }

                if (navController.currentDestination?.id != R.id.searchFragment) {
                    navController.navigate(
                        R.id.searchFragment,
                        Bundle().apply { putString(IntentData.query, newText) }
                    )
                } else {
                    searchViewModel.setQuery(newText)
                }

                return true
            }
        })

        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                if (currentSearchType == SearchType.ONLINE && !isSearchInProgress()) {
                    searchViewModel.setQuery(null)
                    navController.navigate(R.id.openSearch)
                }
                item.setShowAsAction(
                    MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW
                )
                searchView.isIconified = false
                menu.findItem(R.id.action_settings)?.isVisible = false
                searchView.post {
                    searchView.requestFocus()
                    val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                    imm?.showSoftInput(searchAutoComplete, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                val isPlayerExpanded = runOnAudioPlayerFragment {
                    if (binding.playerMotionLayout.progress < 0.95f) {
                        binding.audioPlayerContainer.isClickable = false
                        binding.playerMotionLayout.setTransition(R.id.start, R.id.end)
                        binding.playerMotionLayout.setTransitionDuration(350)
                        binding.playerMotionLayout.transitionToEnd()
                        this@MainActivity.commonPlayerViewModel.isMiniPlayerVisible.value = true
                        minimizePlayerContainerLayout()
                        requestOrientationChange()
                        true
                    } else false
                } || runOnPlayerFragment {
                    if (binding.playerMotionLayout.progress < 0.95f) {
                        binding.playerMotionLayout.setTransition(R.id.start, R.id.end)
                        binding.playerMotionLayout.setTransitionDuration(350)
                        binding.playerMotionLayout.transitionToEnd()
                        commonPlayerViewModel.isMiniPlayerVisible.value = true
                        minimizePlayerContainerLayout()
                        requestOrientationChange()
                        true
                    } else false
                }

                if (isPlayerExpanded) {
                    return false
                }

                item.isVisible = currentSearchType == SearchType.PLAYLIST
                val currentDest = navController.currentDestination?.id
                if (currentDest == R.id.searchFragment || currentDest == R.id.searchResultFragment) {
                    navController.popBackStack(R.id.searchFragment, true)
                }
                val isLibraryScreen = navController.currentDestination?.id == R.id.libraryFragment
                menu.findItem(R.id.action_settings)?.isVisible = isLibraryScreen
                return true
            }
        })

        // handle search queries passed by the intent
        if (savedSearchQuery != null) {
            searchItem.expandActionView()
            searchView.setQuery(savedSearchQuery, true)
            savedSearchQuery = null
        }

        // Fix state restoration bug: forcefully collapse the action view if not in search
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (!isSearchInProgress() && searchItem.isActionViewExpanded) {
                searchItem.collapseActionView()
            }
        }

        return super.onCreateOptionsMenu(menu)
    }

    /**
     * Update the query text in the search bar without opening the search suggestions
     */
    fun setQuerySilent(query: String) {
        if (!this::searchView.isInitialized) return

        shouldOpenSuggestions = false
        searchView.setQuery(query, false)
        shouldOpenSuggestions = true
    }

    /**
     * Update the query text in the search bar and load the search suggestions
     * @param submit whether to immediately load the search results (not suggestions)
     */
    fun setQuery(query: String, submit: Boolean) {
        if (::searchView.isInitialized) searchView.setQuery(query, submit)
    }

    private fun loadIntentData() {
        // If activity is running in PiP mode, then start it in front.
        if (PictureInPictureCompat.isInPictureInPictureMode(this)) {
            val nIntent = Intent(this, MainActivity::class.java)
            nIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(nIntent)
        }

        if (intent?.getBooleanExtra(IntentData.maximizePlayer, false) == true) {
            // attempt to open the current video player fragment
            if (intent?.getBooleanExtra(IntentData.audioOnly, false) == false) {
                runOnPlayerFragment { binding.playerMotionLayout.transitionToStart(); true }
                return
            }

            // if it's an audio only session, attempt to maximize the audio player or create a new one
            if (runOnAudioPlayerFragment { binding.playerMotionLayout.transitionToStart(); true }) return

            val offlinePlayer = intent!!.getBooleanExtra(IntentData.offlinePlayer, false)
            NavigationHelper.openAudioPlayerFragment(this, offlinePlayer = offlinePlayer)
            return
        }

        // navigate to (temporary) playlist or channel if available
        if (navigateToMediaByIntent(intent)) return

        // Get saved search query if available
        intent?.getStringExtra(IntentData.query)?.let {
            savedSearchQuery = it
        }



        // Handle navigation from app shortcuts
        intent?.getStringExtra(IntentData.fragmentToOpen)?.let {
            ShortcutManagerCompat.reportShortcutUsed(this, it)
            when (it) {
                "home", "library" -> navController.navigate(R.id.libraryFragment)
            }
        }
    }

    /**
     * Navigates to the channel, video or playlist provided in the [Intent] if available
     *
     * @return Whether the method handled the event and triggered the navigation to a new fragment
     */
    fun navigateToMediaByIntent(intent: Intent, actionBefore: () -> Unit = {}): Boolean {
        intent.getStringExtra(IntentData.channelId)?.let {
            actionBefore()
            navController.navigate(NavDirections.openChannel(channelId = it))
            return true
        }
        intent.getStringExtra(IntentData.channelName)?.let {
            actionBefore()
            navController.navigate(NavDirections.openChannel(channelName = it))
            return true
        }
        intent.getStringExtra(IntentData.playlistId)?.let {
            actionBefore()
            navController.navigate(NavDirections.openPlaylist(playlistId = it))
            return true
        }
        intent.getStringArrayExtra(IntentData.videoIds)?.let {
            actionBefore()
            ImportTempPlaylistDialog()
                .apply {
                    arguments = Bundle().apply {
                        putString(IntentData.playlistName, intent.getStringExtra(IntentData.playlistName))
                        putStringArray(IntentData.videoIds, it)
                    }
                }
                .show(supportFragmentManager, null)
            return true
        }

        intent.getStringExtra(IntentData.videoId)?.let {
            navigationVideo(it)
            return true
        }

        return false
    }

    private fun navigationVideo(videoId: String) {
        NavigationHelper.navigateVideo(
            context = this@MainActivity,
            playerData = PlayerData(
                videoId = videoId,
                timestamp = intent.getLongExtra(IntentData.timeStamp, 0L)
            ),
        )
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()

        runOnPlayerFragment {
            onUserLeaveHint()
            true
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        this.intent = intent
        loadIntentData()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (runOnPlayerFragment { this@runOnPlayerFragment.onKeyUp(keyCode, event) }) {
            return true
        }

        return super.onKeyUp(keyCode, event)
    }

    fun startPlaylistExport(
        playlistId: String,
        playlistName: String,
        format: ImportFormat,
        includeTimestamp: Boolean
    ) {
        playlistExportFormat = format
        exportPlaylistId = playlistId

        val timestamp = if (includeTimestamp) "_${TextUtils.getFileSafeTimeStampNow()}" else ""
        val extension = if (format == ImportFormat.PIPED) ".json" else ".txt"
        val fileName = "${playlistName.replace(Regex("[^a-zA-Z0-9.-]"), "_")}${timestamp}${extension}"
        createPlaylistsFile.launch(fileName)
    }

    override fun minimizePlayerContainerLayout() {
        binding.mainMotionLayout.setTransitionDuration(350)
        binding.mainMotionLayout.transitionToEnd()
    }

    override fun maximizePlayerContainerLayout() {
        binding.mainMotionLayout.setTransitionDuration(350)
        binding.mainMotionLayout.transitionToStart()
    }

    override fun setPlayerContainerProgress(progress: Float) {
        binding.mainMotionLayout.progress = progress
    }

    override fun isPlayerExpanded(): Boolean {
        if (!this::binding.isInitialized) return false
        val hasPlayer = supportFragmentManager.fragments.any { it is PlayerFragment || it is AudioPlayerFragment }
        val isMini = commonPlayerViewModel.isMiniPlayerVisible.value == true || binding.mainMotionLayout.progress > 0.9f
        return hasPlayer && binding.container.visibility == View.VISIBLE && !isMini
    }

    /**
     * Centralized MVVM Navigation Coordinator:
     * 1. Video Fullscreen -> exit fullscreen
     * 2. Player (Audio/Video) Expanded -> collapse to mini player
     * 3. Active Search Focus -> clear focus and hide keyboard
     * 4. Otherwise -> standard NavController backstack navigation
     */
    private fun setupUnifiedBackCoordinator() {
        val coordinatorCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 1. If video player is in fullscreen -> exit fullscreen
                if (commonPlayerViewModel.isFullscreen.value == true) {
                    val handled = runOnPlayerFragment { unsetFullscreen(); true }
                    if (handled) return
                }

                // 2. If audio player is expanded -> collapse to mini player
                val isAudioExpanded = runOnAudioPlayerFragment {
                    if (binding.playerMotionLayout.progress < 0.95f) {
                        binding.audioPlayerContainer.isClickable = false
                        binding.playerMotionLayout.setTransition(R.id.start, R.id.end)
                        binding.playerMotionLayout.setTransitionDuration(350)
                        binding.playerMotionLayout.transitionToEnd()
                        this@MainActivity.commonPlayerViewModel.isMiniPlayerVisible.value = true
                        minimizePlayerContainerLayout()
                        requestOrientationChange()
                        true
                    } else false
                }
                if (isAudioExpanded) return

                // 3. If video player is expanded -> collapse to mini player
                val isVideoExpanded = runOnPlayerFragment {
                    if (binding.playerMotionLayout.progress < 0.95f) {
                        binding.playerMotionLayout.setTransition(R.id.start, R.id.end)
                        binding.playerMotionLayout.setTransitionDuration(350)
                        binding.playerMotionLayout.transitionToEnd()
                        commonPlayerViewModel.isMiniPlayerVisible.value = true
                        minimizePlayerContainerLayout()
                        requestOrientationChange()
                        true
                    } else false
                }
                if (isVideoExpanded) return

                // 4. If SearchView has active focus or open keyboard -> clear focus
                if (clearSearchViewFocus()) return

                // 5. If on search results or search suggestions -> exit search cleanly back to previous tab
                val currentDestId = navController.currentDestination?.id
                if (currentDestId == R.id.searchResultFragment || currentDestId == R.id.searchFragment) {
                    clearSearchViewFocus()
                    if (this@MainActivity::searchItem.isInitialized && searchItem.isActionViewExpanded) {
                        shouldOpenSuggestions = false
                        searchItem.collapseActionView()
                        shouldOpenSuggestions = true
                    }
                    navController.popBackStack(R.id.searchFragment, true)
                    return
                }

                // 6. Standard backstack navigation
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                if (commonPlayerViewModel.isMiniPlayerVisible.value != true) {
                    runOnAudioPlayerFragment {
                        binding.playerMotionLayout.setTransition(R.id.start, R.id.end)
                        binding.playerMotionLayout.progress = backEvent.progress
                        true
                    }
                    runOnPlayerFragment {
                        binding.playerMotionLayout.setTransition(R.id.start, R.id.end)
                        binding.playerMotionLayout.progress = backEvent.progress
                        true
                    }
                }
            }

            override fun handleOnBackCancelled() {
                if (commonPlayerViewModel.isMiniPlayerVisible.value != true) {
                    runOnAudioPlayerFragment {
                        binding.playerMotionLayout.setTransition(R.id.start, R.id.end)
                        binding.playerMotionLayout.transitionToStart()
                        true
                    }
                    runOnPlayerFragment {
                        binding.playerMotionLayout.setTransition(R.id.start, R.id.end)
                        binding.playerMotionLayout.transitionToStart()
                        true
                    }
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, coordinatorCallback)
    }

    /**
     * @return whether the search view focus was cleared successfully
     */
    override fun clearSearchViewFocus(): Boolean {
        if (!this::searchView.isInitialized || !searchView.anyChildFocused()) return false

        searchView.clearFocus()
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(searchView.windowToken, 0)
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        app.libre.helpers.WifiSyncHelper.stop()
    }
}

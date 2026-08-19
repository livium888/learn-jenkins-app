package com.flashcardreader.app.focus

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One launchable app the user could choose to gate. */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap? = null,
)

data class FocusUiState(
    val enabled: Boolean = false,
    val hasUsageAccess: Boolean = false,
    val canOverlay: Boolean = false,
    val blocked: Set<String> = emptySet(),
    val apps: List<InstalledApp> = emptyList(),
    val loadingApps: Boolean = false,
    val balanceSeconds: Long = 0L,
    /** Diagnostics, so "it just doesn't work" is answerable without a debugger. */
    val serviceRunning: Boolean = false,
    val lastDetected: String? = null,
    val overlayError: String? = null,
)

class FocusGateViewModel(
    private val context: Context,
    private val prefs: FocusPrefs,
    private val bank: CreditBank,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FocusUiState())
    val uiState: StateFlow<FocusUiState> = _uiState

    init {
        refresh()
        viewModelScope.launch {
            bank.observeBalance().collect { seconds ->
                _uiState.update { it.copy(balanceSeconds = seconds) }
            }
        }
    }

    /**
     * Re-reads permission state from the system. Called on every resume because launching a Settings
     * screen always reports RESULT_CANCELED, and some OEM Settings apps never return at all.
     */
    fun refresh() {
        _uiState.update {
            it.copy(
                enabled = prefs.enabled,
                hasUsageAccess = UsageAccess.hasUsageAccess(context),
                canOverlay = UsageAccess.canDrawOverlays(context),
                blocked = prefs.blockedPackages,
                serviceRunning = FocusGateService.isRunning,
                lastDetected = FocusGateService.lastDetected,
                overlayError = FocusGateService.lastOverlayError,
            )
        }
        // The gate can't work without its grants; don't leave it claiming to be on.
        if (prefs.enabled && !UsageAccess.fullyGranted(context)) setEnabled(false)
    }

    fun setEnabled(enabled: Boolean) {
        prefs.enabled = enabled
        _uiState.update { it.copy(enabled = enabled) }
        if (enabled) FocusGateService.start(context) else FocusGateService.stop(context)
    }

    fun toggleBlocked(packageName: String) {
        val next = prefs.blockedPackages.toMutableSet()
        if (!next.add(packageName)) next.remove(packageName)
        prefs.blockedPackages = next
        _uiState.update { it.copy(blocked = next) }
    }

    /** Loads launchable apps off the main thread; icons are decoded once and cached in the model. */
    fun loadApps() {
        if (_uiState.value.apps.isNotEmpty() || _uiState.value.loadingApps) return
        _uiState.update { it.copy(loadingApps = true) }
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = context.packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(intent, 0)
                    .mapNotNull { info ->
                        val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                        if (pkg == context.packageName) return@mapNotNull null
                        val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg)
                        val icon = runCatching {
                            // Scale explicitly: adaptive icons render huge at their native size.
                            info.loadIcon(pm).toBitmap(ICON_PX, ICON_PX).asImageBitmap()
                        }.getOrNull()
                        InstalledApp(pkg, label, icon)
                    }
                    .distinctBy { it.packageName }
                    .sortedBy { it.label.lowercase() }
            }
            _uiState.update { it.copy(apps = apps, loadingApps = false) }
        }
    }

    private companion object {
        const val ICON_PX = 96
    }
}

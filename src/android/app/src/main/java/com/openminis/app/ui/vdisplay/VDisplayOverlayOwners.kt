package com.openminis.app.ui.vdisplay

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * [T-vdisplay-floating] Lifecycle / SavedState / ViewModelStore owners for the
 * global vdisplay overlay window.
 *
 * A WindowManager-attached ComposeView is not part of any Activity view
 * hierarchy, so none of the Local*Owner composition locals resolve unless
 * matching view-tree owners are installed manually. This class bundles the
 * three owners Compose needs, mirroring what `androidx.lifecycle:lifecycle-
 * service` does for Service but for a bare overlay window.
 *
 * Lifecycle:
 *  1. [install] on the root view right after WindowManager.addView (or right
 *     before setContent) — marks the registry RESUMED so recomposition and
 *     rememberSaveable work.
 *  2. [destroy] when the window is removed, when addView throws, or whenever
 *     the overlay is hidden — moves the registry to DESTROYED and clears the
 *     ViewModelStore so nothing (in particular no frame-holding ViewModel)
 *     leaks across overlay sessions.
 *
 * The controller creates a fresh instance per attach, so a destroyed owner is
 * never reused after [destroy].
 */
internal class VDisplayOverlayOwners : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    private var restored = false
    private var destroyed = false

    /** Wires the three view-tree owners onto [root] and starts the registry. */
    fun install(root: View) {
        check(!destroyed) { "overlay owners already destroyed; create a new instance" }
        if (!restored) {
            // performRestore must run while the registry is INITIALIZED; it is
            // a no-args restore because the overlay has no saved-state source —
            // position/scale persistence lives in BackgroundSettingsRepository.
            savedStateController.performRestore(null)
            restored = true
        }
        root.setViewTreeLifecycleOwner(this)
        root.setViewTreeSavedStateRegistryOwner(this)
        root.setViewTreeViewModelStoreOwner(this)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    /** Tears the registry down; idempotent. */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}

/** Hook for tests / future saveInstanceState plumbing. */
@Suppress("unused")
private fun emptyOverlayState(): android.os.Bundle = android.os.Bundle()

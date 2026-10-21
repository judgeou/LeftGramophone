/*
 * Copyright (C) 2026 The Gramophone authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.akanework.gramophone.logic.ui

import android.os.Bundle
import android.os.Parcelable
import androidx.core.os.BundleCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Keeps each directory's list anchor and pixel offset independently of its children. */
internal class FolderScrollState(savedInstanceState: Bundle? = null) {
    private val states = savedInstanceState?.getBundle(KEY)?.let(::Bundle) ?: Bundle()

    fun save(path: List<String>, layoutManager: RecyclerView.LayoutManager?) {
        layoutManager?.onSaveInstanceState()?.let {
            states.putParcelable(path.joinToString("/"), it)
        }
    }

    fun restore(path: List<String>, layoutManager: RecyclerView.LayoutManager?) {
        val state = BundleCompat.getParcelable(states, path.joinToString("/"), Parcelable::class.java)
        if (state != null) {
            layoutManager?.onRestoreInstanceState(state)
        } else if (layoutManager is LinearLayoutManager) {
            layoutManager.scrollToPositionWithOffset(0, 0)
        } else {
            layoutManager?.scrollToPosition(0)
        }
    }

    fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle(KEY, Bundle(states))
    }

    private companion object {
        const val KEY = "FolderScrollStates"
    }
}

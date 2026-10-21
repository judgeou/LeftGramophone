/*
 * Copyright (C) 2026 The Gramophone authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.akanework.gramophone

import android.app.Application
import android.os.Bundle
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.akanework.gramophone.logic.ui.FolderScrollState
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FolderScrollStateTest {
    private lateinit var recyclerView: RecyclerView
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var adapter: Rows

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        layoutManager = LinearLayoutManager(context)
        adapter = Rows()
        recyclerView = RecyclerView(context).apply {
            layoutManager = this@FolderScrollStateTest.layoutManager
            adapter = this@FolderScrollStateTest.adapter
            itemAnimator = null
            // Include an inset, as the real list draws behind system bars.
            setPadding(0, 24, 0, 32)
            clipToPadding = false
        }
        showRows(1000)
    }

    @Test
    fun longParentRestoresExactRowAndOffsetAfterVisitingShortChild() {
        val state = FolderScrollState()
        scrollTo(725, -37)
        val expected = anchor()
        state.save(listOf("Music"), layoutManager)

        showRows(3)
        state.restore(listOf("Music", "Album"), layoutManager)
        layout()
        showRows(1000)
        state.restore(listOf("Music"), layoutManager)
        layout()

        assertEquals(expected, anchor())
    }

    @Test
    fun nestedDirectoriesKeepIndependentPositionsAcrossRepeatedReturns() {
        val state = FolderScrollState()
        val root = emptyList<String>()
        val child = listOf("Music")
        val grandchild = child + "Albums"
        scrollTo(650, -11)
        val rootAnchor = anchor()
        state.save(root, layoutManager)
        showRows(300)
        scrollTo(180, -29)
        val childAnchor = anchor()
        state.save(child, layoutManager)
        showRows(200)
        scrollTo(90, -43)
        state.save(grandchild, layoutManager)

        showRows(300)
        state.restore(child, layoutManager)
        layout()
        assertEquals(childAnchor, anchor())
        state.save(child, layoutManager)
        showRows(1000)
        state.restore(root, layoutManager)
        layout()
        assertEquals(rootAnchor, anchor())
        showRows(300)
        state.restore(child, layoutManager)
        layout()
        assertEquals(childAnchor, anchor())
    }

    @Test
    fun firstVisitStartsAtTopInsteadOfInheritingPreviousDirectoryOffset() {
        val state = FolderScrollState()
        scrollTo(800, -33)
        state.save(listOf("Parent"), layoutManager)

        showRows(500)
        state.restore(listOf("Parent", "New child"), layoutManager)
        layout()

        assertEquals(0 to 0, anchor())
    }

    @Test
    fun sameFolderNameUnderDifferentParentsDoesNotSharePosition() {
        val state = FolderScrollState()
        scrollTo(700, -19)
        val firstAnchor = anchor()
        state.save(listOf("A", "Album"), layoutManager)
        scrollTo(320, -47)
        val secondAnchor = anchor()
        state.save(listOf("B", "Album"), layoutManager)

        state.restore(listOf("A", "Album"), layoutManager)
        layout()
        assertEquals(firstAnchor, anchor())
        state.restore(listOf("B", "Album"), layoutManager)
        layout()
        assertEquals(secondAnchor, anchor())
    }

    @Test
    fun parentPositionSurvivesSavedInstanceStateRoundTripWhileInsideChild() {
        val state = FolderScrollState()
        scrollTo(830, -31)
        val parentAnchor = anchor()
        state.save(listOf("Music"), layoutManager)
        showRows(120)
        scrollTo(70, -17)
        state.save(listOf("Music", "Album"), layoutManager)
        val saved = Bundle().also(state::onSaveInstanceState)
        val parcel = Parcel.obtain()
        val restored = try {
            parcel.writeBundle(saved)
            parcel.setDataPosition(0)
            FolderScrollState(parcel.readBundle(javaClass.classLoader))
        } finally {
            parcel.recycle()
        }

        showRows(1000)
        restored.restore(listOf("Music"), layoutManager)
        layout()

        assertEquals(parentAnchor, anchor())
    }

    private fun showRows(count: Int) {
        adapter.count = count
        @Suppress("NotifyDataSetChanged")
        adapter.notifyDataSetChanged()
        layout()
    }

    private fun scrollTo(position: Int, offset: Int) {
        layoutManager.scrollToPositionWithOffset(position, offset)
        layout()
    }

    private fun layout() {
        recyclerView.measure(
            View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY)
        )
        recyclerView.layout(0, 0, 480, 640)
    }

    private fun anchor(): Pair<Int, Int> {
        val position = layoutManager.findFirstVisibleItemPosition()
        val top = layoutManager.getDecoratedTop(layoutManager.findViewByPosition(position)!!)
        return position to (top - recyclerView.paddingTop)
    }

    private class Rows : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var count = 0

        override fun getItemCount() = count

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(View(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 80)
            }) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
    }
}

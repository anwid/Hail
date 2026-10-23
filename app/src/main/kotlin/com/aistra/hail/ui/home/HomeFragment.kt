package com.aistra.hail.ui.home

import android.graphics.Typeface
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.viewpager2.widget.ViewPager2
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.HailData
import com.aistra.hail.app.HailData.tags
import com.aistra.hail.databinding.FragmentHomeBinding
import com.aistra.hail.extensions.applyDefaultInsetter
import com.aistra.hail.extensions.isLandscape
import com.aistra.hail.extensions.isRtl
import com.aistra.hail.extensions.paddingRelative
import com.aistra.hail.ui.main.ActivityMenuHandler
import com.aistra.hail.ui.main.MainActivity
import com.aistra.hail.ui.main.MainFragment
import com.google.android.material.color.MaterialColors
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class HomeFragment : MainFragment(), ActivityMenuHandler {
    var multiselect: Boolean = false
    val selectedList: MutableList<AppInfo> = mutableListOf()
    private var _binding: FragmentHomeBinding? = null
    val binding get() = _binding!!
    private var mediator: TabLayoutMediator? = null
    private var sorting = false

    // Tab drag-to-reorder state with live interactive slide animation
    private var isTabDragging = false
    private var dragFromIndex = -1
    private var dragCurrentTargetIndex = -1
    private var dragStartX = 0f
    private var dragCurrentRawX = 0f
    private var draggingTabView: View? = null
    private var tabViews: List<View> = emptyList()
    private var originalLefts: List<Int> = emptyList()
    private var originalCenters: List<Float> = emptyList()
    private var originalWidths: List<Int> = emptyList()
    private var originalElevation = 0f

    private val tabAutoScrollRunnable = object : Runnable {
        override fun run() {
            val tabs = _binding?.tabs ?: return
            if (!isTabDragging || draggingTabView == null) return
            val edge = 48 * resources.displayMetrics.density
            val tabsLocation = IntArray(2)
            tabs.getLocationOnScreen(tabsLocation)
            val tabsLeft = tabsLocation[0]
            val tabsRight = tabsLeft + tabs.width
            val delta = when {
                dragCurrentRawX < tabsLeft + edge -> -10
                dragCurrentRawX > tabsRight - edge -> 10
                else -> 0
            }
            if (delta != 0 && tabs.canScrollHorizontally(delta)) {
                val scrollDelta = (delta * resources.displayMetrics.density).toInt()
                tabs.scrollBy(scrollDelta, 0)
                dragStartX -= scrollDelta
                updateTabDragPositions()
            }
            tabs.postOnAnimation(this)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        updateTabMode()
        if (tags.size == 1) binding.tabs.isVisible = false
        binding.pager.adapter = HomeAdapter(this)
        binding.tabs.setSelectedTabIndicator(null)
        mediator = TabLayoutMediator(binding.tabs, binding.pager) { tab, position ->
            val custom = LayoutInflater.from(context).inflate(R.layout.item_tab_header, tab.view, false)
            val textView = custom.findViewById<TextView>(R.id.tab_text)
            textView.text = tags[position].first
            tab.customView = custom
            tab.contentDescription = "${tags[position].first}. ${getString(R.string.tag_drag_hint)}"
            setupTabDrag(tab.view)
        }.also { it.attach() }

        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                updateCustomTabsSelection(tab.position)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        binding.tabs.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) {
                updateTabWidths()
            }
        }
        binding.tabs.post {
            updateTabWidths()
            updateCustomTabsSelection(binding.tabs.selectedTabPosition)
        }

        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                binding.pager.post {
                    if (isResumed && activity.currentDestinationId == R.id.nav_home) {
                        val menu = activity.toolbar.menu
                        onSetupMenu(menu)
                        onPrepareMenu(menu)
                    }
                }
            }
        })

        binding.tabs.applyDefaultInsetter { paddingRelative(isRtl, start = !activity.isLandscape, end = true) }
        return binding.root
    }

    fun getCurrentPagerFragment(): PagerFragment? {
        val pager = _binding?.pager ?: return null
        val position = pager.currentItem
        if (position < 0 || position >= tags.size) return null
        val currentTagId = tags[position].second
        return childFragmentManager.fragments.filterIsInstance<PagerFragment>().firstOrNull { it.tagId == currentTagId }
    }

    override fun onSetupMenu(menu: Menu) {
        getCurrentPagerFragment()?.setupMenu(menu)
    }

    override fun onPrepareMenu(menu: Menu) {
        getCurrentPagerFragment()?.prepareMenu(menu)
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        return getCurrentPagerFragment()?.handleMenuItemClick(item) ?: false
    }

    override fun onResume() {
        super.onResume()
        if (activity.currentDestinationId == R.id.nav_home) {
            val menu = activity.toolbar.menu
            onSetupMenu(menu)
            onPrepareMenu(menu)
        }
    }

    private fun updateCustomTabsSelection(selectedPosition: Int) {
        val tabLayout = _binding?.tabs ?: return
        val primaryColor = MaterialColors.getColor(tabLayout, androidx.appcompat.R.attr.colorPrimary)
        val normalColor = MaterialColors.getColor(tabLayout, com.google.android.material.R.attr.colorOnSurfaceVariant)
        for (i in 0 until tabLayout.tabCount) {
            val custom = tabLayout.getTabAt(i)?.customView ?: continue
            val textView = custom.findViewById<TextView>(R.id.tab_text)
            val indicator = custom.findViewById<View>(R.id.tab_indicator)
            val isSelected = (i == selectedPosition)
            textView?.setTextColor(if (isSelected) primaryColor else normalColor)
            textView?.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
            indicator?.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun updateTabMode() {
        val tabLayout = _binding?.tabs ?: return
        if (tags.size <= 4) {
            tabLayout.tabMode = TabLayout.MODE_FIXED
            tabLayout.tabGravity = TabLayout.GRAVITY_FILL
        } else {
            tabLayout.tabMode = TabLayout.MODE_SCROLLABLE
            tabLayout.tabGravity = TabLayout.GRAVITY_FILL
        }
        updateTabWidths()
    }

    private fun updateTabWidths() {
        val tabLayout = _binding?.tabs ?: return
        if (tags.size <= 4) {
            tabLayout.tabMode = TabLayout.MODE_FIXED
            tabLayout.tabGravity = TabLayout.GRAVITY_FILL
            val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return
            for (i in 0 until tabStrip.childCount) {
                val tabView = tabStrip.getChildAt(i)
                val lp = tabView.layoutParams as? LinearLayout.LayoutParams
                if (lp != null && (lp.width != 0 || lp.weight != 1f)) {
                    lp.width = 0
                    lp.weight = 1f
                    tabView.layoutParams = lp
                }
                tabView.minimumWidth = 0
            }
        } else {
            tabLayout.tabMode = TabLayout.MODE_SCROLLABLE
            tabLayout.tabGravity = TabLayout.GRAVITY_FILL
            val availableWidth = tabLayout.width
            if (availableWidth > 0) {
                val tabWidth = availableWidth / 4
                val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return
                for (i in 0 until tabStrip.childCount) {
                    val tabView = tabStrip.getChildAt(i)
                    val lp = tabView.layoutParams as? LinearLayout.LayoutParams
                    if (lp != null && (lp.width != tabWidth || lp.weight != 0f)) {
                        lp.width = tabWidth
                        lp.weight = 0f
                        tabView.layoutParams = lp
                    }
                    tabView.minimumWidth = tabWidth
                }
            }
        }
    }

    private fun setupTabDrag(tabView: View) {
        tabView.setOnLongClickListener {
            if (sorting || tags.size < 2 || isTabDragging) return@setOnLongClickListener false
            startTabDrag(tabView)
        }
        tabView.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = event.rawX
                    dragCurrentRawX = event.rawX
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isTabDragging && draggingTabView == v) {
                        dragCurrentRawX = event.rawX
                        updateTabDragPositions()
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (isTabDragging && draggingTabView == v) {
                        dragCurrentRawX = event.rawX
                        endTabDrag(cancel = false)
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (isTabDragging && draggingTabView == v) {
                        endTabDrag(cancel = true)
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    private fun startTabDrag(tabView: View): Boolean {
        if (sorting || tags.size < 2 || isTabDragging) return false
        val tabLayout = _binding?.tabs ?: return false
        val from = (0 until tabLayout.tabCount).firstOrNull { tabLayout.getTabAt(it)?.view == tabView } ?: return false

        tabView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        isTabDragging = true
        dragFromIndex = from
        dragCurrentTargetIndex = from
        draggingTabView = tabView

        tabLayout.requestDisallowInterceptTouchEvent(true)
        tabLayout.parent?.requestDisallowInterceptTouchEvent(true)
        binding.pager.isUserInputEnabled = false

        tabViews = (0 until tabLayout.tabCount).mapNotNull { tabLayout.getTabAt(it)?.view }
        originalLefts = tabViews.map { it.left }
        originalCenters = tabViews.map { (it.left + it.right) / 2f }
        originalWidths = tabViews.map { it.width }

        originalElevation = ViewCompat.getElevation(tabView)
        ViewCompat.setElevation(tabView, 24f * resources.displayMetrics.density)
        tabView.animate().scaleX(1.08f).scaleY(1.08f).alpha(0.95f).setDuration(150).start()

        tabLayout.postOnAnimation(tabAutoScrollRunnable)
        return true
    }

    private fun updateTabDragPositions() {
        val tabView = draggingTabView ?: return
        if (dragFromIndex !in tabViews.indices) return

        val deltaX = dragCurrentRawX - dragStartX
        tabView.translationX = deltaX

        val visualCenterX = originalCenters[dragFromIndex] + deltaX

        // Find new target index based on midpoints between adjacent centers
        var newTargetIndex = dragFromIndex
        if (visualCenterX > originalCenters[dragFromIndex]) {
            for (k in (dragFromIndex + 1) until tabViews.size) {
                val mid = (originalCenters[k - 1] + originalCenters[k]) / 2f
                if (visualCenterX > mid) {
                    newTargetIndex = k
                }
            }
        } else if (visualCenterX < originalCenters[dragFromIndex]) {
            for (k in (dragFromIndex - 1) downTo 0) {
                val mid = (originalCenters[k + 1] + originalCenters[k]) / 2f
                if (visualCenterX < mid) {
                    newTargetIndex = k
                }
            }
        }

        if (newTargetIndex != dragCurrentTargetIndex) {
            dragCurrentTargetIndex = newTargetIndex
            tabView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            animateOtherTabs(dragFromIndex, dragCurrentTargetIndex)
        }
    }

    private fun animateOtherTabs(from: Int, target: Int) {
        val fromWidth = originalWidths[from]
        for (k in tabViews.indices) {
            if (k == from) continue
            val targetX = when {
                target > from && k in (from + 1)..target -> -fromWidth.toFloat()
                target < from && k in target until from -> fromWidth.toFloat()
                else -> 0f
            }
            tabViews[k].animate()
                .translationX(targetX)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun endTabDrag(cancel: Boolean = false) {
        if (!isTabDragging) return
        isTabDragging = false
        val tabLayout = _binding?.tabs ?: return
        tabLayout.removeCallbacks(tabAutoScrollRunnable)
        binding.pager.isUserInputEnabled = !sorting

        val tabView = draggingTabView
        val from = dragFromIndex
        val target = if (cancel) from else dragCurrentTargetIndex

        if (tabView != null && from in tabViews.indices && target in tabViews.indices) {
            val fromWidth = originalWidths[from]
            val finalTranslationX = when {
                target > from -> (originalLefts[target] + originalWidths[target] - fromWidth - originalLefts[from]).toFloat()
                target < from -> (originalLefts[target] - originalLefts[from]).toFloat()
                else -> 0f
            }

            tabView.animate()
                .translationX(finalTranslationX)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    ViewCompat.setElevation(tabView, originalElevation)
                    for (v in tabViews) {
                        v.translationX = 0f
                    }
                    if (!cancel && from != target && from in tags.indices && target in tags.indices) {
                        val selectedId = tags.getOrNull(binding.pager.currentItem)?.second
                        tags.moveHomeItem(from, target)
                        HailData.saveTags()
                        refreshTags(selectedId)
                    }
                    draggingTabView = null
                }
                .start()
        } else {
            for (v in tabViews) {
                v.translationX = 0f
                v.scaleX = 1f
                v.scaleY = 1f
                v.alpha = 1f
            }
            draggingTabView = null
        }
    }

    fun refreshTags(selectedId: Int? = tags.getOrNull(binding.pager.currentItem)?.second) {
        updateTabMode()
        val targetPosition = tags.indexOfFirst { it.second == selectedId }.takeIf { it >= 0 } ?: 0
        binding.pager.adapter?.notifyDataSetChanged()
        binding.pager.setCurrentItem(targetPosition, false)
        binding.tabs.post {
            updateTabWidths()
            binding.tabs.getTabAt(targetPosition)?.select()
            updateCustomTabsSelection(targetPosition)
            binding.tabs.setScrollPosition(targetPosition, 0f, true)
        }
        binding.tabs.isVisible = tags.size > 1
    }

    fun setSorting(value: Boolean) {
        sorting = value
        binding.pager.isUserInputEnabled = !value
        for (position in 0 until binding.tabs.tabCount) {
            binding.tabs.getTabAt(position)?.view?.isClickable = !value
        }
    }

    override fun onDestroyView() {
        multiselect = false
        selectedList.clear()
        binding.tabs.removeCallbacks(tabAutoScrollRunnable)
        if (isTabDragging) {
            endTabDrag(cancel = true)
        }
        draggingTabView = null
        tabViews = emptyList()
        sorting = false
        mediator?.detach()
        mediator = null
        super.onDestroyView()
        _binding = null
    }
}

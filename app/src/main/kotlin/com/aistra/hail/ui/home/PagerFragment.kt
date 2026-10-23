package com.aistra.hail.ui.home

import android.os.Bundle
import android.text.InputType
import android.view.*
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SearchView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailApi
import com.aistra.hail.app.HailApi.addTag
import com.aistra.hail.app.HailData
import com.aistra.hail.databinding.DialogInputBinding
import com.aistra.hail.databinding.FragmentPagerBinding
import com.aistra.hail.extensions.*
import com.aistra.hail.ui.main.MainFragment
import com.aistra.hail.ui.theme.AppTheme
import com.aistra.hail.utils.*
import com.aistra.hail.work.HWork
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class PagerFragment : MainFragment(), PagerAdapter.OnItemClickListener, PagerAdapter.OnItemLongClickListener,
    MenuProvider {
    private var query: String = String()
    private var _binding: FragmentPagerBinding? = null
    private val binding get() = _binding!!
    private lateinit var pagerAdapter: PagerAdapter
    private var sorting = false
    private var touchHelper: ItemTouchHelper? = null
    private val sortingBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setSorting(false)
    }
    private var multiselect: Boolean
        set(value) {
            (parentFragment as HomeFragment).multiselect = value
        }
        get() = (parentFragment as HomeFragment).multiselect
    private val selectedList get() = (parentFragment as HomeFragment).selectedList
    private val tabs: TabLayout? get() = (parentFragment as? HomeFragment)?.binding?.tabs
    private val tagId: Int get() = requireArguments().getInt(ARG_TAG_ID)
    private val tag: Pair<String, Int>? get() = HailData.tags.firstOrNull { it.second == tagId }

    companion object {
        private const val ARG_TAG_ID = "tag_id"
        fun newInstance(tagId: Int) = PagerFragment().apply {
            arguments = Bundle().apply { putInt(ARG_TAG_ID, tagId) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val menuHost = requireActivity() as MenuHost
        menuHost.addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        _binding = FragmentPagerBinding.inflate(inflater, container, false)
        pagerAdapter = PagerAdapter(selectedList).apply {
            onItemClickListener = this@PagerFragment
            onItemLongClickListener = this@PagerFragment
        }
        binding.recyclerView.run {
            layoutManager = GridLayoutManager(
                activity, resources.getInteger(
                    if (HailData.compactIcon) R.integer.home_span_compact else R.integer.home_span
                )
            )
            adapter = pagerAdapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    when (newState) {
                        RecyclerView.SCROLL_STATE_IDLE -> activity.fab.run {
                            postDelayed({ if (isResumed && !sorting && tag != null) show() }, 1000)
                        }

                        RecyclerView.SCROLL_STATE_DRAGGING -> activity.fab.hide()
                    }
                }
            })
            applyDefaultInsetter { paddingRelative(isRtl, bottom = isLandscape) }

        }

        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            override fun isLongPressDragEnabled() = sorting
            override fun isItemViewSwipeEnabled() = false
            override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
                if (sorting) super.getMovementFlags(recyclerView, viewHolder) else 0

            override fun onMove(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
            ): Boolean {
                if (!sorting) return false
                return pagerAdapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition).also {
                    if (it) HomeOrder.save(tagId, pagerAdapter.currentList)
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    viewHolder?.itemView?.animate()?.scaleX(1.12f)?.scaleY(1.12f)?.alpha(0.85f)?.setDuration(150)?.start()
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.alpha(1.0f)?.setDuration(150)?.start()
            }
        }).also { helper ->
            helper.attachToRecyclerView(binding.recyclerView)
            pagerAdapter.startDrag = { if (sorting) helper.startDrag(it) }
        }

        binding.refresh.apply {
            setOnRefreshListener {
                updateCurrentList()
                binding.refresh.isRefreshing = false
            }
            applyDefaultInsetter { marginRelative(isRtl, start = !isLandscape, end = true) }
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, sortingBackCallback)
    }

    override fun onResume() {
        super.onResume()
        updateCurrentList()
        updateBarTitle()
        activity.appbar.setLiftOnScrollTargetView(binding.recyclerView)
        activity.fab.setOnClickListener {
            setListFrozen(true, pagerAdapter.currentList.filterNot { it.whitelisted })
        }
        activity.fab.setOnLongClickListener {
            setListFrozen(true)
            true
        }
    }

    override fun onPause() {
        if (sorting) setSorting(false)
        super.onPause()
    }

    private fun setSorting(value: Boolean) {
        sorting = value
        sortingBackCallback.isEnabled = value
        pagerAdapter.sorting = value
        binding.refresh.isEnabled = !value
        (parentFragment as HomeFragment).setSorting(value)
        if (value) {
            query = ""
            multiselect = false
            selectedList.clear()
            tabs?.isVisible = HailData.tags.size > 1
            activity.fab.hide()
            updateCurrentList()
            Snackbar.make(binding.root, R.string.home_sort_hint, Snackbar.LENGTH_LONG).show()
        } else {
            HomeOrder.save(tagId, pagerAdapter.currentList)
            activity.fab.show()
        }
        updateBarTitle()
        activity.invalidateOptionsMenu()
    }

    private fun updateCurrentList() = HailData.checkedList.filter {
        if (query.isEmpty()) tag?.second?.let { tagId -> tagId in it.tagIdList } ?: false
        else ((HailData.nineKeySearch && NineKeySearch.search(
            query, it.packageName, it.name.toString()
        )) || FuzzySearch.search(it.packageName, query) || FuzzySearch.search(
            it.name.toString(), query
        ) || PinyinSearch.searchPinyinAll(it.name.toString(), query))
    }.let { if (query.isEmpty()) HomeOrder.sort(tagId, it) else it.sortedWith(NameComparator) }.let {
        binding.empty.isVisible = it.isEmpty()
        pagerAdapter.submitList(it)
        app.setAutoFreezeService()
    }

    private fun updateBarTitle() {
        activity.supportActionBar?.title =
            if (sorting) getString(R.string.home_sort_title)
            else if (multiselect) getString(R.string.msg_selected, selectedList.size.toString())
            else getString(R.string.app_name)
    }

    override fun onItemClick(info: AppInfo) {
        if (sorting) return
        if (multiselect) {
            if (info in selectedList) selectedList.remove(info)
            else selectedList.add(info)
            updateCurrentList()
            updateBarTitle()
            return
        }
        if (info.applicationInfo == null) {
            Snackbar.make(activity.fab, R.string.app_not_installed, Snackbar.LENGTH_LONG)
                .setAction(R.string.action_remove_home) { removeCheckedApp(info) }.show()
            return
        }
        launchApp(info)
    }

    override fun onItemLongClick(info: AppInfo): Boolean {
        if (info.applicationInfo == null && (!multiselect || info !in selectedList)) {
            exportToClipboard(listOf(info))
            return true
        }
        if (info in selectedList) {
            onMultiSelect()
            return true
        }
        val pkg = info.packageName
        val frozen = AppManager.isAppFrozen(pkg, info.userId)
        val action = getString(if (frozen) R.string.action_unfreeze else R.string.action_freeze)
        val actions = mutableListOf(
            R.string.action_launch,
            if (frozen) R.string.action_unfreeze else R.string.action_freeze,
            R.string.action_deferred_task,
            R.string.action_home_sort,
            if (info.pinned) R.string.action_unpin else R.string.action_pin,
            if (info.whitelisted) R.string.action_remove_whitelist else R.string.action_whitelist,
            R.string.action_tag_set,
            R.string.action_add_pin_shortcut,
            R.string.action_export_clipboard,
            R.string.action_remove_home
        ).apply {
            if (frozen) add(R.string.action_unfreeze_remove_home)
        }
        MaterialAlertDialogBuilder(activity).setTitle(
            if (info.userId == HPackages.myUserId) info.name
            else getString(R.string.app_name_user, info.name, info.userId)
        ).setItems(
            actions.map { getString(it) }.toTypedArray()
        ) { _, which ->
            when (actions[which]) {
                R.string.action_launch -> launchApp(info)
                R.string.action_freeze, R.string.action_unfreeze -> setListFrozen(!frozen, listOf(info))
                R.string.action_deferred_task -> {
                    val values = resources.getIntArray(R.array.deferred_task_values)
                    val entries = arrayOfNulls<String>(values.size)
                    values.forEachIndexed { i, it ->
                        entries[i] = resources.getQuantityString(R.plurals.deferred_task_entry, it, it)
                    }
                    MaterialAlertDialogBuilder(activity).setTitle(R.string.action_deferred_task)
                        .setItems(entries) { _, i ->
                            HWork.setDeferredFrozen(pkg, !frozen, values[i].toLong(), info.userId)
                            Snackbar.make(
                                activity.fab, resources.getQuantityString(
                                    R.plurals.msg_deferred_task, values[i], values[i], action, info.name
                                ), Snackbar.LENGTH_INDEFINITE
                            ).setAction(R.string.action_undo) { HWork.cancelDeferredFrozen(pkg, info.userId) }.show()
                        }.setNegativeButton(android.R.string.cancel, null).show()
                }

                R.string.action_pin, R.string.action_unpin -> {
                    info.pinned = !info.pinned
                    HailData.saveApps()
                    if (query.isEmpty()) {
                        val reordered = pagerAdapter.currentList.toMutableList().apply { remove(info) }
                        reordered.add(if (info.pinned) 0 else reordered.indexOfFirst { !it.pinned }
                            .takeIf { it >= 0 } ?: reordered.size, info)
                        HomeOrder.save(tagId, reordered)
                    }
                    updateCurrentList()
                }

                R.string.action_whitelist, R.string.action_remove_whitelist -> {
                    info.whitelisted = !info.whitelisted
                    HailData.saveApps()
                    updateCurrentList()
                }

                R.string.action_tag_set -> tagDialog(info)

                R.string.action_add_pin_shortcut -> tabs?.takeIf { it.tabCount > 1 }?.let {
                    MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.action_unfreeze_tag)
                        .setItems(HailData.tags.map { it.first }.toTypedArray()) { _, index ->
                            HShortcuts.addPinShortcut(
                                info,
                                info.key,
                                info.name,
                                HailApi.getIntentForPackage(HailApi.ACTION_LAUNCH, pkg, info.userId).addTag(HailData.tags[index].first)
                            )
                        }.setPositiveButton(R.string.action_skip) { _, _ ->
                            HShortcuts.addPinShortcut(
                                info, info.key, info.name, HailApi.getIntentForPackage(HailApi.ACTION_LAUNCH, pkg, info.userId)
                            )
                        }.setNegativeButton(android.R.string.cancel, null).show()
                } ?: HShortcuts.addPinShortcut(
                    info, info.key, info.name, HailApi.getIntentForPackage(HailApi.ACTION_LAUNCH, pkg, info.userId)
                )

                R.string.action_export_clipboard -> exportToClipboard(listOf(info))
                R.string.action_remove_home -> removeCheckedApp(info)
                R.string.action_unfreeze_remove_home -> {
                    setListFrozen(false, listOf(info), false)
                    if (!AppManager.isAppFrozen(pkg, info.userId)) removeCheckedApp(info)
                }
                R.string.action_home_sort -> setSorting(true)
            }
        }.setNeutralButton(R.string.action_details) { _, _ ->
            if (!HPackages.showAppDetails(pkg, info.userId)) HUI.showToast(R.string.activity_not_found)
        }.setNegativeButton(android.R.string.cancel, null).show()
        return true
    }

    private fun tagDialog(info: AppInfo) {
        val checkedItems = BooleanArray(HailData.tags.size) { index ->
            HailData.tags[index].second in info.tagIdList
        }
        MaterialAlertDialogBuilder(activity).setTitle(R.string.action_tag_set).setMultiChoiceItems(
            HailData.tags.map { it.first }.toTypedArray(), checkedItems
        ) { _, index, isChecked ->
            checkedItems[index] = isChecked
        }.setPositiveButton(android.R.string.ok) { _, _ ->
            info.tagIdList.clear()
            checkedItems.forEachIndexed { index, checked ->
                if (checked) info.tagIdList.add(HailData.tags[index].second)
            }
            if (info.tagIdList.isEmpty()) {
                removeCheckedApp(info, false)
            }
            HailData.saveApps()
            updateCurrentList()
        }.setNeutralButton(R.string.action_tag_add) { _, _ ->
            showTagDialog(listOf(info))
        }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun deselect(update: Boolean = true) {
        selectedList.clear()
        if (!update) return
        updateCurrentList()
        updateBarTitle()
    }

    private fun onMultiSelect() {
        MaterialAlertDialogBuilder(activity).setTitle(
            getString(
                R.string.msg_selected, selectedList.size.toString()
            )
        ).setItems(
            intArrayOf(
                R.string.action_freeze,
                R.string.action_unfreeze,
                R.string.action_tag_set,
                R.string.action_export_clipboard,
                R.string.action_remove_home,
                R.string.action_unfreeze_remove_home
            ).map { getString(it) }.toTypedArray()
        ) { _, which ->
            when (which) {
                0 -> {
                    setListFrozen(true, selectedList, false)
                    deselect()
                }

                1 -> {
                    setListFrozen(false, selectedList, false)
                    deselect()
                }

                2 -> triStateTagDialog()

                3 -> {
                    exportToClipboard(selectedList)
                    deselect()
                }

                4 -> {
                    selectedList.forEach { removeCheckedApp(it, false) }
                    HailData.saveApps()
                    deselect()
                }

                5 -> {
                    setListFrozen(false, selectedList, false)
                    selectedList.forEach {
                        if (!AppManager.isAppFrozen(it.packageName, it.userId)) removeCheckedApp(it, false)
                    }
                    HailData.saveApps()
                    deselect()
                }
            }
        }.setNegativeButton(R.string.action_deselect) { _, _ ->
            deselect()
        }.setNeutralButton(R.string.action_select_all) { _, _ ->
            selectedList.addAll(pagerAdapter.currentList.filterNot { it in selectedList })
            updateCurrentList()
            updateBarTitle()
            onMultiSelect()
        }.show()
    }

    private fun triStateTagDialog() {
        val initialStates = Array(HailData.tags.size) { index ->
            val tagId = HailData.tags[index].second
            when (selectedList.count { tagId in it.tagIdList }) {
                selectedList.size -> ToggleableState.On
                0 -> ToggleableState.Off
                else -> ToggleableState.Indeterminate
            }
        }
        val states = mutableStateListOf(*initialStates)
        MaterialAlertDialogBuilder(activity).setTitle(R.string.action_tag_set).setView(ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { AppTheme { TriStateTagList(initialStates, states) } }
        }).setPositiveButton(android.R.string.ok) { _, _ ->
            selectedList.forEach {
                states.forEachIndexed { index, state ->
                    val tagId = HailData.tags[index].second
                    when (state) {
                        ToggleableState.On -> {
                            if (tagId !in it.tagIdList) it.tagIdList.add(tagId)
                        }

                        ToggleableState.Off -> it.tagIdList.remove(tagId)
                        ToggleableState.Indeterminate -> {}
                    }
                }
                if (it.tagIdList.isEmpty()) removeCheckedApp(it, false)
            }
            HailData.saveApps()
            deselect()
        }.setNeutralButton(R.string.action_tag_add) { _, _ ->
            showTagDialog(selectedList)
        }.setNegativeButton(android.R.string.cancel, null).show()
    }

    @Composable
    private fun TriStateTagList(initialStates: Array<ToggleableState>, states: MutableList<ToggleableState>) = Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        HailData.tags.forEachIndexed { index, tag ->
            Row(modifier = Modifier.fillMaxWidth().clickable {
                states[index] = if (initialStates[index] == ToggleableState.Indeterminate) when (states[index]) {
                    ToggleableState.On -> ToggleableState.Off
                    ToggleableState.Off -> ToggleableState.Indeterminate
                    ToggleableState.Indeterminate -> ToggleableState.On
                }
                else if (states[index] == ToggleableState.On) ToggleableState.Off
                else ToggleableState.On
            }.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TriStateCheckbox(
                    state = states[index],
                    onClick = null,
                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.secondary)
                )
                Spacer(modifier = Modifier.width(24.dp))
                Text(
                    text = tag.first,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }

    private fun launchApp(info: AppInfo) {
        if (AppManager.isAppFrozen(info.packageName, info.userId) &&
            AppManager.setAppFrozen(info.packageName, false, info.userId)) {
            updateCurrentList()
        }
        if (info.userId == HPackages.myUserId && HailData.workingMode == HailData.MODE_ISLAND_HIDE) {
            HIsland.ensureLaunchIntentExists(info.packageName)
        }
        if (HPackages.launchApp(info.packageName, info.userId)) {
            HShortcuts.addDynamicShortcut(info.packageName, info.userId)
        } else HUI.showToast(R.string.activity_not_found)
    }

    private fun setListFrozen(
        frozen: Boolean, list: List<AppInfo> = HailData.checkedList, updateList: Boolean = true
    ) {
        if (HailData.workingMode == HailData.MODE_DEFAULT) {
            MaterialAlertDialogBuilder(activity).setMessage(R.string.msg_guide)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        } else if (HailData.workingMode == HailData.MODE_SHIZUKU_HIDE) {
            runCatching { HShizuku.isRoot }.onSuccess {
                if (!it) {
                    MaterialAlertDialogBuilder(activity).setMessage(R.string.shizuku_hide_adb)
                        .setPositiveButton(android.R.string.ok, null).show()
                    return
                }
            }
        }
        val filtered = list.filter { AppManager.isAppFrozen(it.packageName, it.userId) != frozen }
        when (val result = AppManager.setListFrozen(frozen, *filtered.toTypedArray())) {
            null -> HUI.showToast(R.string.permission_denied)
            else -> {
                if (updateList) updateCurrentList()
                HUI.showToast(
                    if (frozen) R.string.msg_freeze else R.string.msg_unfreeze, result
                )
            }
        }
    }

    private fun showTagDialog(list: List<AppInfo>? = null) {
        val tabLayout = tabs ?: return  // The view has been destroyed; return directly.
        val home = parentFragment as? HomeFragment ?: return

        val binding = DialogInputBinding.inflate(layoutInflater)
        binding.inputLayout.setHint(R.string.tag)
        list ?: binding.editText.setText(tag?.first ?: "")
        MaterialAlertDialogBuilder(activity).setTitle(if (list != null) R.string.action_tag_add else R.string.action_tag_set)
            .setView(binding.root).setPositiveButton(android.R.string.ok) { _, _ ->
                val tagName = binding.editText.text.toString().trim()
                if (tagName.isEmpty() || HailData.tags.any { it.first == tagName }) return@setPositiveButton
                if (list != null) { // Add tag
                    var newTagId = tagName.hashCode()
                    while (HailData.tags.any { it.second == newTagId }) newTagId++
                    HailData.tags.add(tagName to newTagId)
                    home.refreshTags(tagId)
                    tabLayout.isVisible = query.isEmpty() && HailData.tags.size > 1
                    if (list == selectedList) triStateTagDialog() else tagDialog(list.first())
                } else { // Rename tag
                    val position = HailData.tags.indexOfFirst { it.second == tagId }
                    if (position < 0) return@setPositiveButton
                    // Keep the ID stable: membership, saved positions, and pager fragments use it.
                    HailData.tags[position] = tagName to tagId
                    home.refreshTags(tagId)
                    tabLayout.isVisible = query.isEmpty() && HailData.tags.size > 1
                }
                HailData.saveTags()
            }.apply {
                if (list != null || tagId == 0) return@apply
                setNeutralButton(R.string.action_tag_remove) { _, _ ->
                    HailData.checkedList.toList().forEach {
                        if (it.tagIdList.remove(tagId) && it.tagIdList.isEmpty()) {
                            removeCheckedApp(it, false)
                        }
                    }
                    HailData.tags.removeAll { it.second == tagId }
                    HomeOrder.remove(tagId)
                    home.refreshTags(0)
                    HailData.saveApps()
                    HailData.saveTags()
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun exportToClipboard(list: List<AppInfo>) {
        if (list.isEmpty()) return
        HUI.copyText(if (list.size > 1 || list.any { it.userId != HPackages.myUserId }) JSONArray().run {
            list.forEach {
                put(if (it.userId == HPackages.myUserId) it.packageName else JSONObject()
                    .put(HailData.KEY_PACKAGE, it.packageName).put("userId", it.userId))
            }
            toString()
        } else list[0].packageName)
        HUI.showToast(
            R.string.msg_exported, if (list.size > 1) list.size.toString() else list[0].name
        )
    }

    private fun importFromClipboard() = runCatching {
        val str = HUI.pasteText() ?: throw IllegalArgumentException()
        val json = if (str.contains('[')) JSONArray(
            str.substring(
                str.indexOf('[')..str.indexOf(']', str.indexOf('['))
            )
        )
        else JSONArray().put(str)
        var i = 0
        for (index in 0 until json.length()) {
            val entry = json.get(index)
            val pkg = if (entry is JSONObject) entry.getString(HailData.KEY_PACKAGE) else entry.toString()
            val userId = if (entry is JSONObject) entry.optInt("userId", HPackages.myUserId) else HPackages.myUserId
            if (userId >= 0 && HPackages.getApplicationInfoOrNull(pkg, userId = userId) != null &&
                !HailData.isChecked(pkg, userId)) {
                HailData.addCheckedApp(pkg, tagId, false, userId)
                i++
            }
        }
        if (i > 0) {
            HailData.saveApps()
            updateCurrentList()
        }
        HUI.showToast(getString(R.string.msg_imported, i.toString()))
    }

    private suspend fun importFrozenApp() = withContext(Dispatchers.IO) {
        HPackages.getApplicationsAcrossUsers()
            .filter { AppManager.isAppFrozen(it.packageName, HPackages.userIdOf(it)) &&
                !HailData.isChecked(it.packageName, HPackages.userIdOf(it)) }
            .onEach { HailData.addCheckedApp(it.packageName, tagId, false, HPackages.userIdOf(it)) }.size
    }

    private fun removeCheckedApp(info: AppInfo, saveApps: Boolean = true) {
        HailData.removeCheckedApp(info.packageName, saveApps, info.userId)
        if (saveApps) updateCurrentList()
    }

    private fun MenuItem.updateIcon() = icon?.setTint(
        MaterialColors.getColor(
            activity.findViewById(R.id.toolbar),
            if (multiselect) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurface
        )
    )

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_home_sort -> setSorting(true)
            R.id.action_sort_done -> setSorting(false)
            R.id.action_edit_current_tag -> showTagDialog()
            R.id.action_multiselect -> {
                multiselect = !multiselect
                item.updateIcon()
                if (multiselect) {
                    updateBarTitle()
                    HUI.showToast(R.string.tap_to_select)
                } else deselect()
            }

            R.id.action_freeze_current -> setListFrozen(true, pagerAdapter.currentList.filterNot { it.whitelisted })

            R.id.action_unfreeze_current -> setListFrozen(false, pagerAdapter.currentList)
            R.id.action_freeze_all -> setListFrozen(true)
            R.id.action_unfreeze_all -> setListFrozen(false)
            R.id.action_freeze_non_whitelisted -> setListFrozen(true, HailData.checkedList.filterNot { it.whitelisted })

            R.id.action_import_clipboard -> importFromClipboard()
            R.id.action_import_frozen -> lifecycleScope.launch {
                val size = importFrozenApp()
                if (size > 0) {
                    HailData.saveApps()
                    updateCurrentList()
                }
                HUI.showToast(getString(R.string.msg_imported, size.toString()))
            }

            R.id.action_export_current -> exportToClipboard(pagerAdapter.currentList)
            R.id.action_export_all -> exportToClipboard(HailData.checkedList)
        }
        return false
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        if (sorting) {
            menu.add(Menu.NONE, R.id.action_sort_done, Menu.NONE, R.string.action_sort_done)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            return
        }
        inflater.inflate(R.menu.menu_home, menu)
        menu.findItem(R.id.action_sort_done).isVisible = false
        val searchView = menu.findItem(R.id.action_search).actionView as SearchView
        if (HailData.nineKeySearch) {
            val editText = searchView.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)
            editText.inputType = InputType.TYPE_CLASS_PHONE
        }
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            private var inited = false
            override fun onQueryTextChange(newText: String): Boolean {
                if (inited) {
                    query = newText
                    tabs?.run {
                        isVisible = query.isEmpty() && tabCount > 1
                    }
                    updateCurrentList()
                } else inited = true
                return true
            }

            override fun onQueryTextSubmit(query: String): Boolean = true
        })
        menu.findItem(R.id.action_multiselect).updateIcon()
    }

    override fun onDestroyView() {
        touchHelper?.attachToRecyclerView(null)
        touchHelper = null
        sortingBackCallback.isEnabled = false
        activity.fab.setOnClickListener(null)
        activity.fab.setOnLongClickListener(null)
        pagerAdapter.onDestroy()
        super.onDestroyView()
        _binding = null
    }
}

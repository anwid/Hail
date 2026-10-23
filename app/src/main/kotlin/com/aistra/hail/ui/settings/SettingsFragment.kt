package com.aistra.hail.ui.settings

import android.Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.automirrored.outlined.Shortcut
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aistra.hail.ui.main.ActivityMenuHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.aistra.hail.HailApp.Companion.app
import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailApi
import com.aistra.hail.app.HailData
import com.aistra.hail.app.TagInfo
import com.aistra.hail.databinding.DialogInputBinding
import com.aistra.hail.databinding.DialogRestoreBinding
import org.json.JSONArray
import org.json.JSONObject
import com.aistra.hail.ui.main.MainActivity
import com.aistra.hail.ui.main.MainFragment
import com.aistra.hail.ui.theme.AppTheme
import com.aistra.hail.utils.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.compose.preference.*
import rikka.shizuku.Shizuku

class SettingsFragment : MainFragment(), ActivityMenuHandler {
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AppTheme {
                    ProvidePreferenceLocals {
                        SettingsScreen()
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingsScreen() {
        val autoFreezeAfterLock = rememberPreferenceState(HailData.AUTO_FREEZE_AFTER_LOCK, false)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            listPreference(
                key = HailData.WORKING_MODE,
                defaultValue = HailData.MODE_DEFAULT,
                onValueChange = ::onWorkingModeChange,
                values = HailData.WORKING_MODE_VALUES,
                entriesId = R.array.working_mode_entries,
                titleId = R.string.working_mode,
                icon = Icons.Outlined.Adb,
                type = ListPreferenceType.ALERT_DIALOG
            )
            switchPreference(
                key = HailData.BIOMETRIC_LOGIN,
                defaultValue = false,
                onValueChange = { _, value ->
                    if (value) resetDynamicShortcuts()
                    true
                },
                titleId = R.string.action_biometric,
                icon = Icons.Outlined.Fingerprint
            )
            horizontalDivider()
            preferenceCategory(key = "customize", title = { Text(text = stringResource(R.string.title_customize)) })
            listPreference(
                key = HailData.APP_THEME,
                defaultValue = HailData.FOLLOW_SYSTEM,
                onValueChange = { _, value ->
                    app.setAppTheme(value)
                    true
                },
                values = HailData.APP_THEME_VALUES,
                entriesId = R.array.app_theme_entries,
                titleId = R.string.app_theme,
                icon = Icons.Outlined.DarkMode
            )
            listPreference(
                key = HailData.ICON_PACK,
                defaultValue = HailData.ACTION_NONE,
                onValueChange = { _, _ ->
                    AppIconCache.clear()
                    true
                },
                values = mutableListOf(HailData.ACTION_NONE).apply {
                    addAll(Intent(Intent.ACTION_MAIN).addCategory("com.anddoes.launcher.THEME").let {
                        if (HTarget.T) app.packageManager.queryIntentActivities(
                            it, PackageManager.ResolveInfoFlags.of(0)
                        ) else app.packageManager.queryIntentActivities(it, 0)
                    }.map { it.activityInfo.packageName })
                },
                titleId = R.string.icon_pack,
                icon = Icons.Outlined.Palette,
                summary = { iconPackName(it) },
                valueToText = ::iconPackName
            )
            switchPreference(
                key = HailData.GRAYSCALE_ICON,
                defaultValue = true,
                titleId = R.string.grayscale_icon,
                icon = Icons.Outlined.FilterBAndW
            )
            switchPreference(
                key = HailData.COMPACT_ICON,
                defaultValue = false,
                titleId = R.string.compact_icon,
                icon = Icons.Outlined.Apps
            )
            switchPreference(
                key = HailData.SYNTHESIZE_ADAPTIVE_ICONS,
                defaultValue = false,
                titleId = R.string.synthesize_adaptive_icons,
                icon = Icons.Outlined.Layers
            )
            sliderPreference(
                key = HailData.HOME_FONT_SIZE,
                defaultValue = 14f,
                title = { Text(text = stringResource(R.string.home_font_size)) },
                valueRange = 11f..16f,
                valueSteps = 4,
                icon = { Icon(imageVector = Icons.Outlined.TextFields, contentDescription = null) },
                valueText = { Text(text = "%.0f".format(it)) },
            )
            switchPreference(
                key = HailData.FUZZY_SEARCH,
                defaultValue = false,
                titleId = R.string.fuzzy_search,
                icon = Icons.AutoMirrored.Outlined.ManageSearch
            )
            switchPreference(
                key = HailData.NINE_KEY_SEARCH,
                defaultValue = false,
                titleId = R.string.nine_key,
                icon = Icons.Outlined.Dialpad
            )
            listPreference(
                key = HailData.TILE_ACTION,
                defaultValue = HailData.tileAction,
                values = HailData.TILE_ACTION_VALUES,
                entriesId = R.array.tile_action_entries,
                titleId = R.string.tile_action,
                icon = Icons.Outlined.DashboardCustomize
            )
            horizontalDivider()
            preferenceCategory(key = "auto_freeze", title = { Text(text = stringResource(R.string.auto_freeze)) })
            switchPreference(
                rememberState = { autoFreezeAfterLock },
                onValueChange = { _, value ->
                    app.setAutoFreezeService(value)
                    true
                },
                titleId = R.string.auto_freeze_after_lock,
                icon = Icons.Outlined.ScreenLockPortrait
            )
            sliderPreference(
                key = HailData.AUTO_FREEZE_DELAY,
                defaultValue = 0f,
                title = { Text(text = stringResource(R.string.auto_freeze_delay)) },
                valueRange = 0f..30f,
                valueSteps = 29,
                enabled = { autoFreezeAfterLock.value },
                icon = { Icon(imageVector = Icons.Outlined.LockClock, contentDescription = null) },
                valueText = { Text(text = "%.0f".format(it)) },
            )
            switchPreference(
                key = HailData.SKIP_WHILE_CHARGING,
                defaultValue = false,
                titleId = R.string.skip_while_charging,
                enabled = autoFreezeAfterLock.value,
                icon = Icons.Outlined.BatteryChargingFull
            )
            switchPreference(
                key = HailData.SKIP_FOREGROUND_APP,
                defaultValue = false,
                onValueChange = { _, value ->
                    if (value && !HSystem.checkOpUsageStats(requireContext())) {
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        false
                    } else true
                },
                titleId = R.string.skip_foreground_app,
                enabled = autoFreezeAfterLock.value,
                icon = Icons.Outlined.Android
            )
            switchPreference(
                key = HailData.SKIP_NOTIFYING_APP,
                defaultValue = false,
                onValueChange = { _, value ->
                    val isGranted = NotificationManagerCompat.getEnabledListenerPackages(requireContext())
                        .contains(requireContext().packageName)
                    if (value && !isGranted) {
                        app.setAutoFreezeServiceEnabled(true)
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        false
                    } else true
                },
                titleId = R.string.skip_notifying_app,
                enabled = autoFreezeAfterLock.value,
                icon = Icons.Outlined.NotificationsActive
            )
            horizontalDivider()
            preferenceCategory(key = "shortcuts", title = { Text(text = stringResource(R.string.title_shortcuts)) })
            preference(
                key = "add_pin_shortcut",
                title = { Text(text = stringResource(R.string.action_add_pin_shortcut)) },
                icon = { Icon(imageVector = Icons.AutoMirrored.Outlined.Shortcut, contentDescription = null) },
                onClick = ::addPinShortcut
            )
            listPreference(
                key = HailData.DYNAMIC_SHORTCUT_ACTION,
                defaultValue = HailData.ACTION_NONE,
                onValueChange = { _, action ->
                    HShortcuts.removeAllDynamicShortcuts()
                    HShortcuts.addDynamicShortcutAction(action)
                    true
                },
                values = HailData.DYNAMIC_SHORTCUT_ACTIONS,
                entriesId = R.array.dynamic_shortcut_entries,
                titleId = R.string.dynamic_shortcut_action,
                icon = Icons.Outlined.AppShortcut
            )
            preference(
                key = "clear_dynamic_shortcuts",
                title = { Text(text = stringResource(R.string.action_clear_dynamic_shortcuts)) },
                icon = { Icon(imageVector = Icons.Outlined.CleaningServices, contentDescription = null) },
                onClick = ::resetDynamicShortcuts
            )
            horizontalDivider()
            preferenceCategory(
                key = "backup_and_restore",
                title = { Text(text = stringResource(R.string.title_backup_and_restore)) }
            )
            preference(
                key = "backup",
                title = { Text(text = stringResource(R.string.action_backup)) },
                summary = { Text(text = stringResource(R.string.summary_backup)) },
                icon = { Icon(imageVector = Icons.Outlined.Backup, contentDescription = null) },
                onClick = ::backupToClipboard
            )
            preference(
                key = "restore",
                title = { Text(text = stringResource(R.string.action_restore)) },
                summary = { Text(text = stringResource(R.string.summary_restore)) },
                icon = { Icon(imageVector = Icons.Outlined.Restore, contentDescription = null) },
                onClick = ::showRestoreDialog
            )
        }
    }

    private fun backupToClipboard() {
        runCatching {
            val root = JSONObject().apply {
                put("version", 1)
                put("timestamp", System.currentTimeMillis())

                // 1. SharedPreferences (settings)
                val settingsObj = JSONObject()
                val sp = PreferenceManager.getDefaultSharedPreferences(app)
                sp.all.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> settingsObj.put(key, value)
                        is Int -> settingsObj.put(key, value)
                        is Long -> settingsObj.put(key, value)
                        is Float -> settingsObj.put(key, value.toDouble())
                        is String -> settingsObj.put(key, value)
                        is Set<*> -> settingsObj.put(key, JSONArray(value.toList()))
                    }
                }
                put("settings", settingsObj)

                // 2. Tags
                val tagsArray = JSONArray()
                HailData.tags.forEach { tag ->
                    tagsArray.put(JSONObject().apply {
                        put(HailData.KEY_TAG, tag.name)
                        put(HailData.KEY_ID, tag.id)
                        put(HailData.WORKING_MODE, tag.workingMode)
                    })
                }
                put("tags", tagsArray)

                // 3. Checked Apps
                val appsArray = JSONArray()
                HailData.checkedList.forEach { appInfo ->
                    appsArray.put(JSONObject().apply {
                        put(HailData.KEY_PACKAGE, appInfo.packageName)
                        put(HailData.KEY_USER_ID, appInfo.userId)
                        put("pinned", appInfo.pinned)
                        put("whitelisted", appInfo.whitelisted)
                        put("tagId", appInfo.tagId)
                        put("tags", JSONArray().put(appInfo.tagId))
                    })
                }
                put("apps", appsArray)

                // 4. Home Order
                val homeOrderSp = app.getSharedPreferences("home_order", Context.MODE_PRIVATE)
                val homeOrderObj = JSONObject()
                homeOrderSp.all.forEach { (key, value) ->
                    if (value is String) {
                        homeOrderObj.put(key, value)
                    }
                }
                put("home_order", homeOrderObj)
            }

            val jsonString = root.toString(2)
            HUI.copyText(jsonString)
            HUI.showToast(R.string.msg_backup_exported)
        }.onFailure {
            HLog.e(it)
            HUI.showToast(R.string.msg_restore_failed)
        }
    }

    private fun showRestoreDialog() {
        val binding = DialogRestoreBinding.inflate(layoutInflater)
        binding.editText.filters = emptyArray()

        binding.btnPaste.setOnClickListener {
            val text = HUI.pasteText()
            if (!text.isNullOrEmpty()) {
                binding.editText.setText(text.trim())
            } else {
                HUI.showToast(R.string.msg_clipboard_empty)
            }
        }

        val clipboardText = HUI.pasteText()
        if (clipboardText != null && (clipboardText.trim().startsWith("{") || clipboardText.trim().startsWith("["))) {
            binding.editText.setText(clipboardText.trim())
        }

        MaterialAlertDialogBuilder(requireActivity())
            .setTitle(R.string.action_restore)
            .setView(binding.root)
            .setPositiveButton(R.string.action_restore) { _, _ ->
                val input = binding.editText.text?.toString()?.trim().orEmpty()
                if (input.isEmpty()) {
                    HUI.showToast(R.string.msg_restore_failed)
                    return@setPositiveButton
                }
                restoreFromJson(input)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun restoreFromJson(jsonString: String) {
        try {
            val trimmed = jsonString.trim()
            if (trimmed.startsWith("{")) {
                val root = JSONObject(trimmed)

                // 1. SharedPreferences
                if (root.has("settings")) {
                    val settingsObj = root.getJSONObject("settings")
                    val sp = PreferenceManager.getDefaultSharedPreferences(app)
                    sp.edit {
                        val keys = settingsObj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            when (val v = settingsObj.get(key)) {
                                is Boolean -> putBoolean(key, v)
                                is Int -> putInt(key, v)
                                is Long -> putLong(key, v)
                                is Double -> putFloat(key, v.toFloat())
                                is String -> putString(key, v)
                                is JSONArray -> {
                                    val set = mutableSetOf<String>()
                                    for (i in 0 until v.length()) set.add(v.getString(i))
                                    putStringSet(key, set)
                                }
                            }
                        }
                    }
                }

                // 2. Tags
                if (root.has("tags")) {
                    val tagsArray = root.getJSONArray("tags")
                    HailData.tags.clear()
                    for (i in 0 until tagsArray.length()) {
                        val tagObj = tagsArray.getJSONObject(i)
                        val name = tagObj.getString(HailData.KEY_TAG)
                        val id = tagObj.getInt(HailData.KEY_ID)
                        val mode = tagObj.optString(HailData.WORKING_MODE, HailData.MODE_DEFAULT).ifEmpty { HailData.MODE_DEFAULT }
                        HailData.tags.add(TagInfo(name, id, mode))
                    }
                    if (HailData.tags.isEmpty()) {
                        HailData.tags.add(TagInfo(app.getString(R.string.label_default), 0, HailData.MODE_DEFAULT))
                    }
                    HailData.saveTags()
                }

                // 3. Apps
                if (root.has("apps")) {
                    val appsArray = root.getJSONArray("apps")
                    HailData.checkedList.clear()
                    for (i in 0 until appsArray.length()) {
                        val appObj = appsArray.getJSONObject(i)
                        val pkg = appObj.getString(HailData.KEY_PACKAGE)
                        val userId = appObj.optInt("userId", HPackages.myUserId)
                        val pinned = appObj.optBoolean("pinned", false)
                        val whitelisted = appObj.optBoolean("whitelisted", false)
                        val tagId = if (appObj.has("tagId")) {
                            appObj.getInt("tagId")
                        } else if (appObj.has("tags")) {
                            val arr = appObj.getJSONArray("tags")
                            if (arr.length() > 0) arr.getInt(0) else 0
                        } else {
                            appObj.optInt(HailData.KEY_TAG, 0)
                        }
                        HailData.checkedList.add(
                            AppInfo(
                                packageName = pkg,
                                pinned = pinned,
                                whitelisted = whitelisted,
                                tagIdList = mutableListOf(tagId),
                                userId = userId
                            )
                        )
                    }
                    HailData.saveApps()
                }

                // 4. Home Order
                if (root.has("home_order")) {
                    val homeOrderObj = root.getJSONObject("home_order")
                    val homeOrderSp = app.getSharedPreferences("home_order", Context.MODE_PRIVATE)
                    homeOrderSp.edit {
                        clear()
                        val keys = homeOrderObj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            putString(key, homeOrderObj.getString(key))
                        }
                    }
                }
            } else if (trimmed.startsWith("[")) {
                // Support restoring raw apps array
                val appsArray = JSONArray(trimmed)
                HailData.checkedList.clear()
                for (i in 0 until appsArray.length()) {
                    val entry = appsArray.get(i)
                    val pkg = if (entry is JSONObject) entry.getString(HailData.KEY_PACKAGE) else entry.toString()
                    val userId = if (entry is JSONObject) entry.optInt("userId", HPackages.myUserId) else HPackages.myUserId
                    val tagId = if (entry is JSONObject) entry.optInt("tagId", 0) else 0
                    val pinned = if (entry is JSONObject) entry.optBoolean("pinned", false) else false
                    val whitelisted = if (entry is JSONObject) entry.optBoolean("whitelisted", false) else false
                    HailData.checkedList.add(
                        AppInfo(
                            packageName = pkg,
                            pinned = pinned,
                            whitelisted = whitelisted,
                            tagIdList = mutableListOf(tagId),
                            userId = userId
                        )
                    )
                }
                HailData.saveApps()
            } else {
                HUI.showToast(R.string.msg_restore_failed)
                return
            }

            // Refresh environment
            app.setAppTheme(HailData.appTheme)
            app.setAutoFreezeService(HailData.autoFreezeAfterLock)
            AppIconCache.clear()
            if (HailData.workingMode.startsWith(HailData.DHIZUKU) || HailData.tags.any { it.workingMode.startsWith(HailData.DHIZUKU) }) {
                runCatching { HDhizuku.init() }
            }

            HUI.showToast(R.string.msg_restore_success)
            activity?.recreate()
        } catch (e: Exception) {
            HLog.e(e)
            HUI.showToast(R.string.msg_restore_failed)
        }
    }

    private fun LazyListScope.horizontalDivider() = item { HorizontalDivider() }

    private fun LazyListScope.switchPreference(
        rememberState: @Composable () -> MutableState<Boolean>,
        onValueChange: (MutableState<Boolean>, Boolean) -> Boolean = { _, _ -> true },
        @StringRes titleId: Int,
        enabled: Boolean = true,
        icon: ImageVector,
    ) = item(key = titleId, contentType = "SwitchPreference") {
        val state = rememberState()
        SwitchPreference(
            value = state.value,
            onValueChange = { if (onValueChange(state, it)) state.value = it },
            title = { Text(text = stringResource(titleId)) },
            enabled = enabled,
            icon = { Icon(imageVector = icon, contentDescription = null) })
    }

    private fun LazyListScope.switchPreference(
        key: String,
        defaultValue: Boolean,
        onValueChange: (MutableState<Boolean>, Boolean) -> Boolean = { _, _ -> true },
        @StringRes titleId: Int,
        enabled: Boolean = true,
        icon: ImageVector,
    ) = switchPreference(
        rememberState = { rememberPreferenceState(key, defaultValue) },
        onValueChange = onValueChange,
        titleId = titleId,
        enabled = enabled,
        icon = icon
    )

    private fun LazyListScope.listPreference(
        key: String,
        defaultValue: String,
        onValueChange: (MutableState<String>, String) -> Boolean = { _, _ -> true },
        values: List<String>,
        @StringRes titleId: Int,
        icon: ImageVector,
        summary: @Composable (String) -> String,
        type: ListPreferenceType = ListPreferenceType.DROPDOWN_MENU,
        valueToText: (String) -> String
    ) = item(key = key, contentType = "ListPreference") {
        val state = rememberPreferenceState(key, defaultValue)
        ListPreference(
            value = state.value,
            onValueChange = { if (onValueChange(state, it)) state.value = it },
            values = values,
            title = { Text(text = stringResource(titleId)) },
            icon = { Icon(imageVector = icon, contentDescription = null) },
            summary = { Text(text = summary(state.value)) },
            type = type,
            valueToText = { AnnotatedString(valueToText(it)) })
    }

    private fun LazyListScope.listPreference(
        key: String,
        defaultValue: String,
        onValueChange: (MutableState<String>, String) -> Boolean = { _, _ -> true },
        values: List<String>,
        @ArrayRes entriesId: Int,
        @StringRes titleId: Int,
        icon: ImageVector,
        type: ListPreferenceType = ListPreferenceType.DROPDOWN_MENU
    ) = listPreference(
        key = key,
        defaultValue = defaultValue,
        onValueChange = onValueChange,
        values = values,
        titleId = titleId,
        icon = icon,
        summary = { it.toEntry(values, entriesId) },
        type = type,
        valueToText = { it.toEntry(values, entriesId) })

    private fun String.toEntry(values: List<String>, @ArrayRes entriesId: Int): String =
        resources.getStringArray(entriesId)[values.indexOf(this)]

    private fun resetDynamicShortcuts() {
        HShortcuts.removeAllDynamicShortcuts()
        HShortcuts.addDynamicShortcutAction(HailData.dynamicShortcutAction)
    }

    private fun iconPackName(pack: String): String = if (pack == HailData.ACTION_NONE) getString(R.string.action_none)
    else HPackages.getApplicationInfoOrNull(pack)?.loadLabel(app.packageManager)?.toString() ?: pack

    private fun addPinShortcut() {
        MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.action_add_pin_shortcut)
            .setItems(R.array.pin_shortcut_entries) { _, which ->
                when (which) {
                    0 -> MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.action_freeze_tag)
                        .setItems(HailData.tags.map { it.first }.toTypedArray()) { _, index ->
                            val tag = HailData.tags[index].first
                            HShortcuts.addPinShortcut(
                                AppCompatResources.getDrawable(
                                    requireContext(), R.drawable.ic_round_frozen_shortcut
                                )!!,
                                HailApi.ACTION_FREEZE_TAG + tag,
                                tag,
                                HailApi.getIntentForTag(HailApi.ACTION_FREEZE_TAG, tag)
                            )
                        }.setNegativeButton(android.R.string.cancel, null).show()

                    1 -> MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.action_unfreeze_tag)
                        .setItems(HailData.tags.map { it.first }.toTypedArray()) { _, index ->
                            val tag = HailData.tags[index].first
                            HShortcuts.addPinShortcut(
                                AppCompatResources.getDrawable(
                                    requireContext(), R.drawable.ic_round_unfrozen_shortcut
                                )!!,
                                HailApi.ACTION_UNFREEZE_TAG + tag,
                                tag,
                                HailApi.getIntentForTag(HailApi.ACTION_UNFREEZE_TAG, tag)
                            )
                        }.setNegativeButton(android.R.string.cancel, null).show()

                    2 -> HShortcuts.addPinShortcut(
                        AppCompatResources.getDrawable(
                            requireContext(), R.drawable.ic_round_frozen_shortcut
                        )!!,
                        HailApi.ACTION_FREEZE_ALL,
                        getString(R.string.action_freeze_all),
                        Intent(HailApi.ACTION_FREEZE_ALL)
                    )

                    3 -> HShortcuts.addPinShortcut(
                        AppCompatResources.getDrawable(
                            requireContext(), R.drawable.ic_round_unfrozen_shortcut
                        )!!,
                        HailApi.ACTION_UNFREEZE_ALL,
                        getString(R.string.action_unfreeze_all),
                        Intent(HailApi.ACTION_UNFREEZE_ALL)
                    )

                    4 -> HShortcuts.addPinShortcut(
                        AppCompatResources.getDrawable(
                            requireContext(), R.drawable.ic_round_frozen_shortcut
                        )!!,
                        HailApi.ACTION_FREEZE_NON_WHITELISTED,
                        getString(R.string.action_freeze_non_whitelisted),
                        Intent(HailApi.ACTION_FREEZE_NON_WHITELISTED)
                    )

                    5 -> HShortcuts.addPinShortcut(
                        AppCompatResources.getDrawable(
                            requireContext(), R.drawable.ic_outline_lock_shortcut
                        )!!, HailApi.ACTION_LOCK, getString(R.string.action_lock), Intent(HailApi.ACTION_LOCK)
                    )

                    6 -> HShortcuts.addPinShortcut(
                        AppCompatResources.getDrawable(
                            requireContext(), R.drawable.ic_outline_lock_shortcut
                        )!!,
                        HailApi.ACTION_LOCK_FREEZE,
                        getString(R.string.action_lock_freeze),
                        Intent(HailApi.ACTION_LOCK_FREEZE)
                    )
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    fun onWorkingModeChange(rememberState: MutableState<String>, mode: String): Boolean {
        // Show/hide terminal menu.
        activity.invalidateOptionsMenu()
        when {
            mode.startsWith(HailData.OWNER) -> if (!HPolicy.isDeviceOwnerActive) {
                MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.title_set_owner)
                    .setMessage(getString(R.string.msg_set_owner, HPolicy.ADB_COMMAND))
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(android.R.string.copy) { _, _ -> HUI.copyText(HPolicy.ADB_COMMAND) }.show()
                    .findViewById<MaterialTextView>(android.R.id.message)?.setTextIsSelectable(true)
                return false
            }

            mode.startsWith(HailData.DHIZUKU) -> return runCatching {
                Dhizuku.init(app)
                when {
                    Dhizuku.isPermissionGranted() -> true
                    else -> {
                        lifecycleScope.launch {
                            val result = callbackFlow {
                                Dhizuku.requestPermission(object : DhizukuRequestPermissionListener() {
                                    override fun onRequestPermission(grantResult: Int) {
                                        trySendBlocking(grantResult == PackageManager.PERMISSION_GRANTED)
                                    }
                                })
                                awaitClose()
                            }.first()
                            if (result) {
                                rememberState.value = mode
                                if (HTarget.O) HDhizuku.setDelegatedScopes()
                            }
                        }
                        false
                    }
                }
            }.getOrElse {
                HLog.e(it)
                HUI.showToast(R.string.permission_denied)
                false
            }

            mode.startsWith(HailData.SU) -> if (!HShell.checkSU) {
                HUI.showToast(R.string.permission_denied)
                return false
            }

            mode == HailData.MODE_JAILBREAK_DISABLE -> if (
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    CHANGE_COMPONENT_ENABLED_STATE
                ) == PackageManager.PERMISSION_DENIED
                && !HShell.grantPermission(requireContext().packageName, CHANGE_COMPONENT_ENABLED_STATE)
            ) {
                HUI.showToast(R.string.permission_denied)
                return false
            }

            mode.startsWith(HailData.SHIZUKU) -> return runCatching {
                when {
                    Shizuku.isPreV11() -> throw IllegalStateException("unsupported shizuku version")
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> true
                    Shizuku.shouldShowRequestPermissionRationale() -> {
                        HUI.showToast(R.string.permission_denied)
                        false
                    }

                    else -> {
                        lifecycleScope.launch {
                            val result = callbackFlow {
                                val shizukuRequestCode = 0
                                val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
                                    if (requestCode != shizukuRequestCode) return@OnRequestPermissionResultListener
                                    trySendBlocking(grantResult == PackageManager.PERMISSION_GRANTED)
                                }
                                Shizuku.addRequestPermissionResultListener(listener)
                                Shizuku.requestPermission(shizukuRequestCode)
                                awaitClose {
                                    Shizuku.removeRequestPermissionResultListener(listener)
                                }
                            }.first()
                            if (result) rememberState.value = mode
                        }
                        false
                    }
                }
            }.getOrElse {
                HLog.e(it)
                HUI.showToast(R.string.shizuku_missing)
                false
            }

            mode.startsWith(HailData.ISLAND) -> return runCatching {
                when {
                    mode == HailData.MODE_ISLAND_HIDE && HIsland.freezePermissionGranted() -> true
                    mode == HailData.MODE_ISLAND_SUSPEND && HIsland.suspendPermissionGranted() -> true
                    else -> {
                        lifecycleScope.launch {
                            requestPermissionLauncher.launch(
                                if (mode == HailData.MODE_ISLAND_HIDE) HIsland.PERMISSION_FREEZE_PACKAGE
                                else HIsland.PERMISSION_SUSPEND_PACKAGE
                            )
                        }
                        false
                    }
                }
            }.getOrElse {
                HLog.e(it)
                HUI.showToast(R.string.permission_denied)
                false
            }.also {
                if (it) {
                    HIsland.checkOwnerApp()
                }
            }

            mode.startsWith(HailData.PRIVAPP) -> if (!HPackages.isPrivilegedApp(app.packageName)) {
                HUI.showToast(R.string.permission_denied)
                return false
            }
        }

        return true
    }

    private suspend fun onTerminalResult(exitValue: Int, msg: String?) = withContext(Dispatchers.Main) {
        if (exitValue == 0 && msg.isNullOrBlank()) return@withContext
        MaterialAlertDialogBuilder(requireActivity()).apply {
            if (!msg.isNullOrBlank()) {
                if (exitValue != 0) {
                    setTitle(getString(R.string.operation_failed, exitValue.toString()))
                }
                setMessage(msg)
                setNeutralButton(android.R.string.copy) { _, _ -> HUI.copyText(msg) }
            } else if (exitValue != 0) {
                setMessage(getString(R.string.operation_failed, exitValue.toString()))
            }
        }.setPositiveButton(android.R.string.ok, null).show().findViewById<MaterialTextView>(android.R.id.message)
            ?.setTextIsSelectable(true)
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_terminal -> showTerminalDialog()
            R.id.action_remove_owner -> (requireActivity() as MainActivity).ownerRemoveDialog()
            R.id.action_help -> HUI.openLink(HailData.URL_README)
            else -> return false
        }
        return true
    }

    override fun onPrepareMenu(menu: Menu) {
        if (HailData.workingMode.startsWith(HailData.SU) || HailData.workingMode.startsWith(
                HailData.SHIZUKU
            )
        ) menu.findItem(R.id.action_terminal)?.isVisible = true
        else if (HPolicy.isDeviceOwnerActive) menu.findItem(R.id.action_remove_owner)?.isVisible = true
    }

    override fun onResume() {
        super.onResume()
        if (activity.currentDestinationId == R.id.nav_settings) {
            onPrepareMenu(activity.toolbar.menu)
        }
    }

    private fun showTerminalDialog() {
        val binding = DialogInputBinding.inflate(layoutInflater)
        binding.inputLayout.setHint(R.string.command)
        binding.editText.run {
            setSingleLine()
            filters = arrayOf()
        }
        MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.action_terminal).setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    val result = AppManager.execute(binding.editText.text.toString())
                    onTerminalResult(result.first, result.second)
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }
}
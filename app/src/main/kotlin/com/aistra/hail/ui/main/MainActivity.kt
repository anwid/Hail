package com.aistra.hail.ui.main

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.MenuCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.aistra.hail.R
import com.aistra.hail.app.HailData
import com.aistra.hail.databinding.ActivityMainBinding
import com.aistra.hail.extensions.*
import com.aistra.hail.utils.HPolicy
import com.aistra.hail.utils.HUI
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

class MainActivity : AppCompatActivity(), NavController.OnDestinationChangedListener {
    lateinit var binding: ActivityMainBinding
    lateinit var fab: ExtendedFloatingActionButton
    lateinit var appbar: AppBarLayout
    lateinit var toolbar: com.google.android.material.appbar.MaterialToolbar
    var currentDestinationId = R.id.nav_home

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val binding = initView()
        if (!HailData.biometricLogin || BiometricManager.from(this)
                .canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) != BiometricManager.BIOMETRIC_SUCCESS
        ) return
        binding.root.isVisible = false
        val biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    HUI.showToast(errString)
                    finishAndRemoveTask()
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    binding.root.isVisible = true
                }
            })
        val promptInfo = BiometricPrompt.PromptInfo.Builder().setTitle(getString(R.string.action_biometric))
            .setSubtitle(getString(R.string.msg_biometric)).setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
        biometricPrompt.authenticate(promptInfo)
    }

    private fun initView() = ActivityMainBinding.inflate(layoutInflater).apply {
        this@MainActivity.binding = this
        setContentView(root)
        fab = appBarMain.fab
        appbar = appBarMain.appBarLayout
        toolbar = appBarMain.toolbar
        toolbar.layoutTransition = null
        setSupportActionBar(toolbar)

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        navController.addOnDestinationChangedListener(this@MainActivity)
        val appBarConfiguration = AppBarConfiguration.Builder(
            R.id.nav_home, R.id.nav_apps, R.id.nav_settings
        ).build()
        setupActionBarWithNavController(navController, appBarConfiguration)
        bottomNav?.setupWithNavController(navController)
        navRail?.setupWithNavController(navController)

        val isRtl = isRtl
        val isLandscape = isLandscape
        appBarMain.appBarLayout.applyDefaultInsetter {
            paddingRelative(isRtl, start = !isLandscape, end = true, top = true)
        }
        bottomNav?.applyDefaultInsetter { paddingRelative(isRtl, start = true, end = true, bottom = true) }
        navRail?.applyDefaultInsetter { paddingRelative(isRtl, start = true, top = true, bottom = true) }
        fab.applyDefaultInsetter { marginRelative(isRtl, end = true, bottom = isLandscape) }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        if (menu == null) return false
        menu.clear()
        val menuRes = when (currentDestinationId) {
            R.id.nav_apps -> R.menu.menu_apps
            R.id.nav_settings -> R.menu.menu_settings
            else -> R.menu.menu_home
        }
        menuInflater.inflate(menuRes, menu)
        MenuCompat.setGroupDividerEnabled(menu, true)

        when (currentDestinationId) {
            R.id.nav_settings -> {
                menu.findItem(R.id.action_remove_owner)?.isVisible = HPolicy.isDeviceOwnerActive
            }
            R.id.nav_apps -> {
                menu.findItem(
                    when (HailData.sortBy) {
                        HailData.SORT_INSTALL -> R.id.sort_by_install
                        HailData.SORT_UPDATE -> R.id.sort_by_update
                        else -> R.id.sort_by_name
                    }
                )?.isChecked = true
                menu.findItem(
                    if (HailData.filterSystemApps) R.id.filter_system_apps else R.id.filter_user_apps
                )?.isChecked = true
                menu.findItem(R.id.filter_frozen_apps)?.isChecked = HailData.filterFrozenApps
                menu.findItem(R.id.filter_unfrozen_apps)?.isChecked = HailData.filterUnfrozenApps
            }
            R.id.nav_home -> {
                menu.findItem(R.id.action_sort_done)?.isVisible = false
            }
        }

        val handler = getActiveMenuHandler()
        handler?.onSetupMenu(menu)
        handler?.onPrepareMenu(menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        if (menu == null) return false
        getActiveMenuHandler()?.onPrepareMenu(menu)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (getActiveMenuHandler()?.onMenuItemClick(item) == true) {
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun getActiveMenuHandler(): ActivityMenuHandler? {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        val primary = navHostFragment?.childFragmentManager?.primaryNavigationFragment
            ?: navHostFragment?.childFragmentManager?.fragments?.firstOrNull { it.isVisible }
        return primary as? ActivityMenuHandler
    }

    fun ownerRemoveDialog() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.title_remove_owner).setMessage(R.string.msg_remove_owner)
            .setPositiveButton(R.string.action_continue) { _, _ ->
                HPolicy.setOrganizationName()
                HPolicy.removeDeviceOwner()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    /* override fun onStop() {
        super.onStop()
        if (HailData.biometricLogin) finishAndRemoveTask()
    } */

    override fun onDestinationChanged(
        controller: NavController, destination: NavDestination, arguments: Bundle?
    ) {
        fab.tag = destination.id == R.id.nav_home
        if (fab.tag == true) fab.show() else fab.hide()
        appbar.setLifted(false)
        appbar.setLiftOnScrollTargetView(null)

        if (currentDestinationId != destination.id) {
            currentDestinationId = destination.id
            invalidateOptionsMenu()
        }
    }
}

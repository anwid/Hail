package com.aistra.hail.ui.main

import android.view.Menu
import android.view.MenuItem

interface ActivityMenuHandler {
    fun onSetupMenu(menu: Menu) {}
    fun onPrepareMenu(menu: Menu) {}
    fun onMenuItemClick(item: MenuItem): Boolean = false
}

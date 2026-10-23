package com.aistra.hail.ui.home

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.aistra.hail.app.HailData.tags

class HomeAdapter(fragment: HomeFragment) : FragmentStateAdapter(fragment) {
    override fun getItemCount(): Int = tags.size

    override fun createFragment(position: Int): Fragment = PagerFragment.newInstance(tags[position].second)

    override fun getItemId(position: Int): Long = tags[position].second.toLong() and 0xffffffffL

    override fun containsItem(itemId: Long): Boolean = tags.any { (it.second.toLong() and 0xffffffffL) == itemId }
}

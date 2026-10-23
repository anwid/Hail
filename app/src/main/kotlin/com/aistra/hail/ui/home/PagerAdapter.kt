package com.aistra.hail.ui.home

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.HailData
import com.aistra.hail.utils.AppIconCache
import com.aistra.hail.utils.HPackages
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Job

class PagerAdapter(private val selectedList: List<AppInfo>) : RecyclerView.Adapter<PagerAdapter.ViewHolder>() {
    private val items = mutableListOf<AppInfo>()
    private var flags: Map<String, Int> = emptyMap()
    val currentList: List<AppInfo> get() = items.toList()
    var sorting = false
    var startDrag: ((RecyclerView.ViewHolder) -> Unit)? = null
    private val holders = mutableSetOf<ViewHolder>()
    lateinit var onItemClickListener: OnItemClickListener
    lateinit var onItemLongClickListener: OnItemLongClickListener

    override fun getItemCount(): Int = items.size

    fun submitList(list: List<AppInfo>) {
        val old = items.toList()
        val oldFlags = flags
        val newFlags = list.associate { it.key to it.getFlag(selectedList) }
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = list.size
            override fun areItemsTheSame(oldPosition: Int, newPosition: Int) =
                old[oldPosition].key == list[newPosition].key
            override fun areContentsTheSame(oldPosition: Int, newPosition: Int) =
                oldFlags[old[oldPosition].key] == newFlags[list[newPosition].key]
        })
        items.clear()
        items.addAll(list)
        flags = newFlags
        diff.dispatchUpdatesTo(this)
    }

    fun move(from: Int, to: Int): Boolean {
        if (!items.moveHomeItem(from, to)) return false
        notifyItemMoved(from, to)
        return true
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_home, parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val info = items[position]
        holders.add(holder)
        holder.loadIconJob?.cancel()
        holder.itemView.run {
            isActivated = sorting
            setOnClickListener { if (!sorting) onItemClickListener.onItemClick(info) }
            setOnLongClickListener {
                if (sorting) {
                    startDrag?.invoke(holder)
                    true
                } else onItemLongClickListener.onItemLongClick(info)
            }
            findViewById<ImageView>(R.id.app_icon).run {
                info.applicationInfo?.let {
                    holder.loadIconJob = AppIconCache.loadIconBitmapAsync(
                        context,
                        it,
                        info.userId,
                        this,
                        HailData.grayscaleIcon && info.state == AppInfo.State.FROZEN
                    )
                } ?: run {
                    setImageDrawable(context.packageManager.defaultActivityIcon)
                    colorFilter = null
                }
            }
            findViewById<TextView>(R.id.app_name).run {
                text = buildString {
                    if (!HailData.grayscaleIcon && info.state == AppInfo.State.FROZEN) append("\u2744\uFE0F")
                    if (info.whitelisted) append("\uD83D\uDD12")
                    append(if (info.userId == HPackages.myUserId) info.name
                    else context.getString(R.string.app_name_user, info.name, info.userId))
                }
                isEnabled = !HailData.grayscaleIcon || info.state != AppInfo.State.FROZEN
                when {
                    info in selectedList -> setTextColor(
                        MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
                    )

                    info.state == AppInfo.State.NOT_FOUND -> setTextColor(
                        MaterialColors.getColor(this, androidx.appcompat.R.attr.colorError)
                    )

                    else -> setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, HailData.homeFontSize)
            }
        }
    }

    fun onDestroy() {
        holders.forEach { it.loadIconJob?.cancel() }
        holders.clear()
        startDrag = null
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.loadIconJob?.cancel()
        holders.remove(holder)
        super.onViewRecycled(holder)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        var loadIconJob: Job? = null
    }

    interface OnItemClickListener {
        fun onItemClick(info: AppInfo)
    }

    interface OnItemLongClickListener {
        fun onItemLongClick(info: AppInfo): Boolean
    }
}

private fun AppInfo.getFlag(selectedList: List<AppInfo>) =
    (1 shl state.ordinal) or (this in selectedList).shl(3) or whitelisted.shl(4)

private fun Boolean.shl(bitCount: Int) = if (this) 1 shl bitCount else 0

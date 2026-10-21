package com.diego.pasoapaso.ui

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.diego.pasoapaso.tracking.WalkRecorder
import com.diego.pasoapaso.tracking.SessionState
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.diego.pasoapaso.R
import com.diego.pasoapaso.data.Walk
import com.diego.pasoapaso.data.WalkDatabase
import com.diego.pasoapaso.databinding.ActivityMainBinding
import com.diego.pasoapaso.databinding.ItemWalkBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val adapter = WalkAdapter { openWalk(it.id) }
    private var walks = emptyList<Walk>()
    private var filter: LocalDate? = null
    private var loading: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemInsets()
        @Suppress("DEPRECATION")
        val info = packageManager.getPackageInfo(packageName, 0)
        binding.appVersion.text = getString(R.string.version, info.versionName ?: "")
        filter = savedInstanceState?.getString("date")?.let(LocalDate::parse)
        binding.historyList.layoutManager = LinearLayoutManager(this)
        binding.historyList.adapter = adapter
        val languageTags = arrayOf("es", "en", "ja")
        val languageNames = arrayOf("Español", "English", "日本語")
        val currentLanguage = resources.configuration.locales[0].language
        val selectedLanguage = languageTags.indexOf(currentLanguage).coerceAtLeast(0)
        binding.changeLanguage.text = languageNames[selectedLanguage]
        binding.changeLanguage.contentDescription = getString(R.string.language_current, languageNames[selectedLanguage])
        binding.changeLanguage.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.language_title)
                .setSingleChoiceItems(languageNames, selectedLanguage) { dialog, index ->
                    dialog.dismiss()
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTags[index]))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        binding.newWalk.setOnClickListener { openWalk(null) }
        binding.viewLast.setOnClickListener { walks.firstOrNull()?.let { openWalk(it.id) } }
        binding.retryHome.setOnClickListener { refresh() }
        binding.retryHistory.setOnClickListener { refresh() }
        binding.filterDate.setOnClickListener {
            val date = filter ?: LocalDate.now()
            DatePickerDialog(this, { _, year, month, day ->
                filter = LocalDate.of(year, month + 1, day)
                render()
            }, date.year, date.monthValue - 1, date.dayOfMonth).show()
        }
        binding.clearFilter.setOnClickListener { filter = null; render() }
        binding.navigation.setOnItemSelectedListener { item ->
            binding.homePage.isVisible = item.itemId == R.id.nav_home
            binding.historyPage.isVisible = item.itemId == R.id.nav_history
            binding.profilePage.isVisible = item.itemId == R.id.nav_profile
            true
        }
        binding.navigation.selectedItemId = savedInstanceState?.getInt("tab") ?: R.id.nav_home
    }

    override fun onResume() {
        super.onResume()
        val session = WalkRecorder.get(application).ui.value
        binding.newWalk.setText(if (session.detail == null && session.state != SessionState.IDLE)
            R.string.return_walk else R.string.new_walk)
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tab", binding.navigation.selectedItemId)
        outState.putString("date", filter?.toString())
        super.onSaveInstanceState(outState)
    }

    private fun refresh() {
        loading?.cancel()
        loading = lifecycleScope.launch {
            try {
                walks = withContext(Dispatchers.IO) { WalkDatabase.get(this@MainActivity).listWalks() }
                binding.retryHome.isVisible = false
                binding.retryHistory.isVisible = false
                render()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                binding.lastWalkText.setText(R.string.load_error)
                binding.historyEmpty.setText(R.string.load_error)
                binding.historyEmpty.isVisible = true
                binding.retryHome.isVisible = true
                binding.retryHistory.isVisible = true
            }
        }
    }

    private fun render() {
        binding.summary.text = resources.getQuantityString(R.plurals.stats, walks.size, kilometers(walks.sumOf { it.distanceMeters }), walks.size)
        val last = walks.firstOrNull()
        binding.viewLast.isVisible = last != null
        binding.lastWalkText.text = last?.let { getString(R.string.last_walk_summary, walkName(it), dateText(it.startedAt), kilometers(it.distanceMeters)) } ?: getString(R.string.home_empty)
        binding.filterDate.text = filter?.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.SHORT).withLocale(resources.configuration.locales[0])) ?: getString(R.string.filter_date)
        binding.clearFilter.isVisible = filter != null
        val selected = walks.filter { filter == null || Instant.ofEpochMilli(it.startedAt).atZone(ZoneId.systemDefault()).toLocalDate() == filter }
        adapter.submitList(selected)
        binding.historyEmpty.isVisible = selected.isEmpty()
        binding.historyEmpty.setText(if (filter == null) R.string.empty_history else R.string.empty_date)
    }

    private fun openWalk(id: String?) { startActivity(Intent(this, WalkActivity::class.java).putExtra(WalkActivity.EXTRA_ID, id)) }
}

private class WalkAdapter(private val onClick: (Walk) -> Unit) : ListAdapter<Walk, WalkAdapter.Holder>(object : DiffUtil.ItemCallback<Walk>() {
    override fun areItemsTheSame(oldItem: Walk, newItem: Walk) = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: Walk, newItem: Walk) = oldItem == newItem
}) {
    class Holder(val binding: ItemWalkBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ItemWalkBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val walk = getItem(position)
        holder.binding.walkName.text = holder.itemView.context.walkName(walk)
        holder.binding.walkDate.text = holder.itemView.context.dateText(walk.startedAt)
        holder.binding.walkMetrics.text = holder.itemView.context.getString(R.string.metric_summary, kilometers(walk.distanceMeters), elapsedText(walk.durationMs))
        holder.binding.root.setOnClickListener { onClick(walk) }
    }
}

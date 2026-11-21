package app.calmlauncher.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.calmlauncher.CalmGraph
import app.calmlauncher.data.ExemptCategory
import app.calmlauncher.data.ExemptSource
import app.calmlauncher.data.Mappers
import app.calmlauncher.data.SettingsStore
import app.calmlauncher.engine.AppKey
import app.calmlauncher.engine.MAX_SESSION_MIN
import app.calmlauncher.engine.MIN_SESSION_MIN
import app.calmlauncher.engine.TrackedApp
import app.calmlauncher.onboarding.OnboardingActivity
import app.calmlauncher.ui.CalmActivity
import app.calmlauncher.util.Apps
import app.calmlauncher.util.failOpen
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Prefs
import app.olauncher.databinding.CalmSettingsBinding
import app.olauncher.databinding.CalmSettingsHeaderBinding
import app.olauncher.databinding.CalmSettingsRowBinding
import app.olauncher.helper.getAppsList
import app.olauncher.helper.showToast
import kotlinx.coroutines.launch

/**
 * Screen-time settings, reached from one row in upstream settings (spec FR-024): tracked and
 * exempt apps, session lengths, warning lead, extension wait and cooldown.
 */
class ScreenTimeSettingsActivity : CalmActivity() {

    private lateinit var b: CalmSettingsBinding
    private lateinit var header: CalmSettingsHeaderBinding
    private val repo get() = CalmGraph.repository
    private val adapter = Adapter()

    private var allRows: List<Row> = emptyList()

    /** One launchable app in one user profile. */
    private data class Row(val key: AppKey, val label: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = CalmSettingsBinding.inflate(layoutInflater)
        // The LayoutManager must be set before inflating a view against the RecyclerView as parent:
        // RecyclerView needs it to create the header's layout params.
        b.calmList.layoutManager = LinearLayoutManager(this)
        header = CalmSettingsHeaderBinding.inflate(layoutInflater, b.calmList, false)
        setContentView(b.root)
        b.calmList.adapter = adapter

        header.calmPermissions.setOnClickListener { OnboardingActivity.open(this, null) }
        header.calmSave.setOnClickListener { failOpen("ScreenTimeSettings.save") { save() } }
        header.calmSearch.doAfterTextChanged { failOpen("ScreenTimeSettings.search") { filter() } }

        repo.whenLoaded {
            fillFields()
            loadApps()
        }
    }

    // ------------------------------------------------------------------------------ session fields

    private fun fillFields() {
        header.calmPresets.setText(repo.settings.defaultPresets.joinToString(", "))
        header.calmCooldown.setText(repo.settings.defaultCooldownMin.toString())
        header.calmWarning.setText(repo.config.warningLeadSec.toString())
        header.calmExtensionWait.setText(repo.config.extensionWaitSec.toString())
    }

    private fun save() {
        val presets = Mappers.parsePresets(header.calmPresets.text.toString())
        val cooldown = header.calmCooldown.text.toString().trim().toIntOrNull()
        val warning = header.calmWarning.text.toString().trim().toIntOrNull()
        val wait = header.calmExtensionWait.text.toString().trim().toIntOrNull()
        val valid = presets.isNotEmpty() && presets.all { it in MIN_SESSION_MIN..MAX_SESSION_MIN } &&
            cooldown != null && cooldown in 0..TrackedApp.MAX_COOLDOWN_MIN &&
            warning != null && warning in 0..300 && wait != null && wait in 0..300
        header.calmSettingsError.isVisible = !valid
        if (!valid) {
            header.calmSettingsError.text = getString(R.string.calm_settings_invalid_presets)
            return
        }
        val clean = presets.distinct().sorted()
        repo.editSettings({ p ->
            p[SettingsStore.Keys.DEFAULT_PRESETS_CSV] = Mappers.presetsToCsv(clean)
            p[SettingsStore.Keys.DEFAULT_COOLDOWN_MIN] = cooldown!!
            p[SettingsStore.Keys.WARNING_LEAD_SEC] = warning!!
            p[SettingsStore.Keys.EXTENSION_WAIT_SEC] = wait!!
        }) {
            // Session lengths and cooldown apply to every tracked app.
            repo.tracked.values.forEach { repo.saveTracked(TrackedApp.of(it.key, clean, cooldown!!)) }
            fillFields()
            showToast(getString(R.string.calm_settings_saved))
        }
    }

    // ----------------------------------------------------------------------------------- app list

    private fun loadApps() {
        lifecycleScope.launch {
            val apps = getAppsList(this@ScreenTimeSettingsActivity, Prefs(this@ScreenTimeSettingsActivity), includeRegularApps = true, includeHiddenApps = true)
            val mySerial = Apps.mySerial(this@ScreenTimeSettingsActivity)
            allRows = apps.filterIsInstance<AppModel.App>()
                .map { app ->
                    val key = Apps.keyOf(this@ScreenTimeSettingsActivity, app.appPackage, app.user)
                    val label = if (key.userSerial == mySerial) app.appLabel else getString(R.string.calm_settings_work_suffix, app.appLabel)
                    Row(key, label)
                }
                .distinctBy { it.key }
            filter()
        }
    }

    private fun filter() {
        val query = header.calmSearch.text.toString().trim()
        adapter.rows = if (query.isEmpty()) allRows else {
            allRows.filter { it.label.contains(query, ignoreCase = true) || it.key.pkg.contains(query, ignoreCase = true) }
        }
        adapter.notifyDataSetChanged()
    }

    private fun toggleTracked(row: Row) {
        repo.setTracked(row.key, on = !repo.tracked.containsKey(row.key))
        adapter.notifyDataSetChanged()
    }

    private fun toggleExempt(row: Row) {
        val pkg = row.key.pkg
        val existing = repo.exemptRows[pkg]
        when {
            existing == null -> {
                repo.addExempt(pkg)
                adapter.notifyDataSetChanged()
            }

            existing.category == ExemptCategory.SYSTEM -> showToast(getString(R.string.calm_always_exempt))

            // Removing a default exemption needs an explicit confirmation (spec FR-021).
            existing.source == ExemptSource.DEFAULT -> AlertDialog.Builder(this)
                .setTitle(R.string.calm_remove_default_title)
                .setMessage(getString(R.string.calm_remove_default_message, row.label))
                .setPositiveButton(R.string.calm_remove) { _, _ ->
                    repo.removeExempt(pkg)
                    adapter.notifyDataSetChanged()
                }
                .setNegativeButton(R.string.calm_cancel, null)
                .show()

            else -> {
                repo.removeExempt(pkg)
                adapter.notifyDataSetChanged()
            }
        }
    }

    /** Long-press on a tracked app: its own session lengths and cooldown. */
    private fun editTrackedApp(row: Row) {
        val tracked = repo.tracked[row.key] ?: return
        val padding = (24 * resources.displayMetrics.density).toInt()
        val presets = EditText(this).apply {
            setText(tracked.presetsMin.joinToString(", "))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val cooldown = EditText(this).apply {
            setText(tracked.cooldownMin.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(TextView(this@ScreenTimeSettingsActivity).apply { setText(R.string.calm_per_app_presets) })
            addView(presets)
            addView(TextView(this@ScreenTimeSettingsActivity).apply { setText(R.string.calm_per_app_cooldown) })
            addView(cooldown)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.calm_per_app_title, row.label))
            .setView(form)
            .setPositiveButton(R.string.calm_settings_save) { _, _ ->
                val list = Mappers.parsePresets(presets.text.toString())
                val minutes = cooldown.text.toString().trim().toIntOrNull()
                if (list.isEmpty() || list.any { it !in MIN_SESSION_MIN..MAX_SESSION_MIN } || minutes == null || minutes !in 0..TrackedApp.MAX_COOLDOWN_MIN) {
                    showToast(getString(R.string.calm_settings_invalid_presets))
                } else {
                    repo.saveTracked(TrackedApp.of(row.key, list, minutes))
                }
            }
            .setNegativeButton(R.string.calm_cancel, null)
            .show()
    }

    // ------------------------------------------------------------------------------------ adapter

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var rows: List<Row> = emptyList()

        override fun getItemCount(): Int = rows.size + 1
        override fun getItemViewType(position: Int): Int = if (position == 0) TYPE_HEADER else TYPE_ROW

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == TYPE_HEADER) {
                (header.root.parent as? ViewGroup)?.removeView(header.root)
                object : RecyclerView.ViewHolder(header.root) {}
            } else {
                RowHolder(CalmSettingsRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is RowHolder) holder.bind(rows[position - 1])
        }
    }

    private inner class RowHolder(private val rb: CalmSettingsRowBinding) : RecyclerView.ViewHolder(rb.root) {
        fun bind(row: Row) {
            rb.calmRowLabel.text = row.label
            val tracked = repo.tracked.containsKey(row.key)
            val exempt = repo.isExempt(row.key.pkg)
            style(rb.calmRowTrack, tracked)
            style(rb.calmRowExempt, exempt)
            rb.calmRowTrack.setOnClickListener { failOpen("ScreenTimeSettings.track") { toggleTracked(row) } }
            rb.calmRowExempt.setOnClickListener { failOpen("ScreenTimeSettings.exempt") { toggleExempt(row) } }
            rb.root.setOnLongClickListener {
                failOpen("ScreenTimeSettings.edit") { editTrackedApp(row) }
                true
            }
        }

        /** Text-only toggle: bold and full colour when on, faint when off. */
        private fun style(view: TextView, on: Boolean) {
            view.alpha = if (on) 1f else 0.35f
            view.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            view.contentDescription = "${view.text}: ${if (on) "on" else "off"}"
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ROW = 1

        fun open(context: Context) {
            context.startActivity(Intent(context, ScreenTimeSettingsActivity::class.java))
        }
    }
}

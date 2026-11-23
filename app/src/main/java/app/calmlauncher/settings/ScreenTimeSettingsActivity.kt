package app.calmlauncher.settings

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
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
import app.calmlauncher.engine.TrackedApp
import app.calmlauncher.exempt.ExemptRules
import app.calmlauncher.onboarding.OnboardingActivity
import app.calmlauncher.onboarding.PermissionStatus
import app.calmlauncher.session.TimeLeft
import app.calmlauncher.session.TimeLeftOverlay
import app.calmlauncher.ui.Accent
import app.calmlauncher.ui.CalmActivity
import app.calmlauncher.ui.CalmLook
import app.calmlauncher.ui.FlowLayout
import app.calmlauncher.util.Apps
import app.calmlauncher.util.failOpen
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Prefs
import app.olauncher.databinding.CalmAppEditorBinding
import app.olauncher.databinding.CalmSettingsBinding
import app.olauncher.databinding.CalmSettingsHeaderBinding
import app.olauncher.databinding.CalmSettingsRowBinding
import app.olauncher.databinding.CalmStepperRowBinding
import app.olauncher.helper.getAppsList
import app.olauncher.helper.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Screen-time settings, reached from one row in upstream settings (spec FR-024): setup status,
 * session lengths, cooldown, warning and extension wait, the time-left reminder (FR-029), the
 * accent colour (FR-030), and the list of apps to track or exempt (FR-004).
 *
 * Everything is saved the moment it changes; there is no Save button. The list is a RecyclerView
 * (it only creates views for the rows on screen) whose first item is the settings header.
 */
class ScreenTimeSettingsActivity : CalmActivity() {

    private lateinit var b: CalmSettingsBinding
    private lateinit var header: CalmSettingsHeaderBinding
    private val repo get() = CalmGraph.repository
    private val adapter = Adapter()

    private var allRows: List<Row> = emptyList()
    private val icons = HashMap<AppKey, Drawable>()

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
        header.calmSearch.doAfterTextChanged { failOpen("ScreenTimeSettings.search") { filter() } }
        bindAccent()

        repo.whenLoaded {
            bindSessionSettings()
            bindTimeLeftChoice()
            loadApps()
        }
    }

    override fun onResume() {
        super.onResume()
        failOpen("ScreenTimeSettings.permissions") { showPermissionStatus() }
    }

    private fun showPermissionStatus() {
        val p = PermissionStatus.read(this)
        val missing = listOf(p.notifications, p.overlay, p.usageAccess, p.exactAlarms, p.battery).count { !it }
        header.calmPermissionsStatus.text = if (missing == 0) {
            getString(R.string.calm_settings_permissions_ok)
        } else {
            resources.getQuantityString(R.plurals.calm_settings_permissions_missing, missing, missing)
        }
    }

    // ------------------------------------------------------------------------------ session settings

    private fun bindSessionSettings() {
        renderPresetChips(header.calmPresetChips, repo.settings.defaultPresets) { saveDefaultPresets(it) }
        bindStepper(
            header.calmCooldownRow, R.string.calm_settings_cooldown, R.string.calm_settings_cooldown_hint,
            SettingSteps.COOLDOWN_MIN, repo.settings.defaultCooldownMin, ::formatCooldown,
        ) { saveDefaultCooldown(it) }
        bindStepper(
            header.calmWarningRow, R.string.calm_settings_warning, R.string.calm_settings_warning_hint,
            SettingSteps.WARNING_SEC, repo.config.warningLeadSec, { formatSeconds(it, R.string.calm_value_off) },
        ) { sec -> repo.editSettings({ p -> p[SettingsStore.Keys.WARNING_LEAD_SEC] = sec }) }
        bindStepper(
            header.calmExtensionRow, R.string.calm_settings_extension_wait, R.string.calm_settings_extension_wait_hint,
            SettingSteps.EXTENSION_WAIT_SEC, repo.config.extensionWaitSec, { formatSeconds(it, R.string.calm_value_none) },
        ) { sec -> repo.editSettings({ p -> p[SettingsStore.Keys.EXTENSION_WAIT_SEC] = sec }) }
    }

    /** The default session lengths, applied to every tracked app (each keeps its own cooldown). */
    private fun saveDefaultPresets(presets: List<Int>) {
        repo.editSettings({ p -> p[SettingsStore.Keys.DEFAULT_PRESETS_CSV] = Mappers.presetsToCsv(presets) }) {
            repo.tracked.values.forEach { repo.saveTracked(TrackedApp.of(it.key, presets, it.cooldownMin)) }
            refreshRows()
        }
    }

    /** The default cooldown, applied to every tracked app (each keeps its own session lengths). */
    private fun saveDefaultCooldown(minutes: Int) {
        repo.editSettings({ p -> p[SettingsStore.Keys.DEFAULT_COOLDOWN_MIN] = minutes }) {
            repo.tracked.values.forEach { repo.saveTracked(TrackedApp.of(it.key, it.presetsMin, minutes)) }
            refreshRows()
        }
    }

    /**
     * Session lengths as chips: each chip removes itself when tapped, "+ Add" asks for a number.
     * [onChange] gets the new list after every change; the chips redraw themselves.
     */
    private fun renderPresetChips(flow: FlowLayout, presets: List<Int>, onChange: (List<Int>) -> Unit) {
        flow.removeAllViews()
        presets.forEach { minutes ->
            val chip = toggle(getString(R.string.calm_preset_chip, minutes), selected = true) {
                val next = SettingSteps.removePreset(presets, minutes)
                if (next == null) {
                    showToast(getString(R.string.calm_preset_keep_one))
                } else {
                    renderPresetChips(flow, next, onChange)
                    onChange(next)
                }
            }
            chip.contentDescription = getString(R.string.calm_preset_remove, minutes)
            flow.addView(chip)
        }
        if (presets.size < SettingSteps.MAX_PRESETS) {
            flow.addView(toggle(getString(R.string.calm_preset_add), selected = false) {
                askMinutes { minutes ->
                    val next = SettingSteps.addPreset(presets, minutes)
                    if (next == null) {
                        showToast(getString(R.string.calm_preset_invalid))
                    } else {
                        renderPresetChips(flow, next, onChange)
                        onChange(next)
                    }
                }
            })
        }
    }

    private fun askMinutes(onPicked: (Int) -> Unit) {
        val pad = dp(24)
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(2))
            hint = getString(R.string.calm_prompt_other_hint)
            backgroundTintList = ColorStateList.valueOf(themeColor(R.attr.calmAccent))
        }
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.calm_preset_add_title)
            .setView(box)
            .setPositiveButton(R.string.calm_preset_add_ok) { _, _ ->
                val minutes = field.text.toString().trim().toIntOrNull()
                if (minutes == null) showToast(getString(R.string.calm_preset_invalid)) else onPicked(minutes)
            }
            .setNegativeButton(R.string.calm_cancel, null)
            .show()
        field.requestFocus()
    }

    /** Wires one "label (−) value (+)" row. The value shows at once; [save] stores it. */
    private fun bindStepper(
        row: CalmStepperRowBinding,
        @StringRes label: Int,
        @StringRes hint: Int?,
        options: List<Int>,
        initial: Int,
        format: (Int) -> String,
        save: (Int) -> Unit,
    ) {
        row.stepLabel.setText(label)
        row.stepHint.isVisible = hint != null
        hint?.let { row.stepHint.setText(it) }
        row.stepMinus.contentDescription = getString(R.string.calm_step_less, getString(label))
        row.stepPlus.contentDescription = getString(R.string.calm_step_more, getString(label))
        var current = initial
        fun show() {
            row.stepValue.text = format(current)
            listOf(row.stepMinus to false, row.stepPlus to true).forEach { (button, up) ->
                val atEnd = SettingSteps.atEnd(options, current, up)
                button.isEnabled = !atEnd
                button.alpha = if (atEnd) 0.3f else 1f
            }
        }
        fun move(up: Boolean) = failOpen("ScreenTimeSettings.step") {
            val next = SettingSteps.step(options, current, up)
            if (next != current) {
                current = next
                show()
                save(next)
            }
        }
        row.stepMinus.setOnClickListener { move(up = false) }
        row.stepPlus.setOnClickListener { move(up = true) }
        show()
    }

    private fun formatCooldown(minutes: Int): String =
        if (minutes == 0) getString(R.string.calm_value_off) else getString(R.string.calm_minutes_short, minutes)

    private fun formatSeconds(sec: Int, @StringRes zero: Int): String = when {
        sec == 0 -> getString(zero)
        sec < 60 -> getString(R.string.calm_seconds_short, sec)
        sec % 60 == 0 -> getString(R.string.calm_minutes_short, sec / 60)
        else -> getString(R.string.calm_minutes_seconds_short, sec / 60, sec % 60)
    }

    // ------------------------------------------------------------------------- time left (FR-029)

    /** Pill / bar / off. Saved at once and applied to a running session straight away. */
    private fun bindTimeLeftChoice() {
        val toggles = mapOf(
            TimeLeft.Display.PILL to header.calmTimeLeftPill,
            TimeLeft.Display.BAR to header.calmTimeLeftBar,
            TimeLeft.Display.OFF to header.calmTimeLeftOff,
        )
        fun show(choice: TimeLeft.Display) {
            toggles.forEach { (display, view) -> view.isSelected = display == choice }
            header.calmTimeLeftHint.setText(
                when (choice) {
                    TimeLeft.Display.PILL -> R.string.calm_time_left_pill_hint
                    TimeLeft.Display.BAR -> R.string.calm_time_left_bar_hint
                    TimeLeft.Display.OFF -> R.string.calm_time_left_off_hint
                },
            )
        }
        toggles.forEach { (display, view) ->
            view.setOnClickListener {
                failOpen("ScreenTimeSettings.timeLeft") {
                    show(display)
                    repo.editSettings({ p -> p[SettingsStore.Keys.TIME_LEFT_DISPLAY] = display.name }) {
                        TimeLeftOverlay.sync(this, repo.state, repo.timeLeftDisplay)
                    }
                }
            }
        }
        show(repo.timeLeftDisplay)
    }

    // -------------------------------------------------------------------------- accent (FR-030)

    private fun bindAccent() {
        val current = CalmLook.accent(this)
        header.calmAccentName.setText(current.label)
        val size = dp(44)
        val flow = header.calmAccentSwatches
        flow.horizontalSpacing = dp(10)
        flow.verticalSpacing = dp(10)
        flow.removeAllViews()
        Accent.entries.forEach { accent ->
            val swatch = View(this).apply {
                background = swatchDrawable(ContextCompat.getColor(this@ScreenTimeSettingsActivity, accent.color), accent == current)
                isSelected = accent == current
                contentDescription = getString(accent.label)
                isClickable = true
                isFocusable = true
                setOnClickListener { failOpen("ScreenTimeSettings.accent") { chooseAccent(accent) } }
            }
            flow.addView(swatch, ViewGroup.LayoutParams(size, size))
        }
    }

    private fun chooseAccent(accent: Accent) {
        if (accent == CalmLook.accent(this)) return
        CalmLook.setAccent(this, accent)
        // The pill or bar of a running session takes the new colour at once.
        TimeLeftOverlay.restyle(this, repo.state, repo.timeLeftDisplay)
        // Screens pick their colours when they are created, so this one is created again.
        recreate()
    }

    /** A colour dot; the chosen one gets a ring in the text colour around it. */
    private fun swatchDrawable(@ColorInt color: Int, selected: Boolean): Drawable {
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(1), themeColor(R.attr.primaryColorTrans50))
        }
        if (!selected) return LayerDrawable(arrayOf(dot)).apply { setLayerInset(0, dp(4), dp(4), dp(4), dp(4)) }
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(2), themeColor(R.attr.primaryColor))
        }
        return LayerDrawable(arrayOf(ring, dot)).apply { setLayerInset(1, dp(6), dp(6), dp(6), dp(6)) }
    }

    // ----------------------------------------------------------------------------------- app list

    private fun loadApps() {
        lifecycleScope.launch {
            val apps = getAppsList(this@ScreenTimeSettingsActivity, Prefs(this@ScreenTimeSettingsActivity), includeRegularApps = true, includeHiddenApps = true)
            val mySerial = Apps.mySerial(this@ScreenTimeSettingsActivity)
            val rows = apps.filterIsInstance<AppModel.App>()
                .map { app ->
                    val key = Apps.keyOf(this@ScreenTimeSettingsActivity, app.appPackage, app.user)
                    val label = if (key.userSerial == mySerial) app.appLabel else getString(R.string.calm_settings_work_suffix, app.appLabel)
                    Row(key, label)
                }
                .distinctBy { it.key }
            // Tracked apps first, then exempt ones, then the rest. Sorted once, so a row does not
            // jump away while the user is tapping its toggles.
            allRows = rows.sortedWith(compareBy<Row>({ rank(it) }, { it.label.lowercase() }))
            filter()
            loadIcons(allRows)
        }
    }

    private fun rank(row: Row): Int = when {
        repo.tracked.containsKey(row.key) -> 0
        repo.isExempt(row.key.pkg) -> 1
        else -> 2
    }

    /** Icons are read on a background thread after the list is up, then shown in one go. */
    private fun loadIcons(rows: List<Row>) {
        lifecycleScope.launch {
            val app = applicationContext
            val loaded = withContext(Dispatchers.IO) {
                rows.mapNotNull { row -> Apps.icon(app, row.key)?.let { row.key to it } }
            }
            icons.putAll(loaded)
            adapter.notifyDataSetChanged()
        }
    }

    private fun filter() {
        val query = header.calmSearch.text.toString().trim()
        adapter.rows = if (query.isEmpty()) allRows else {
            allRows.filter { it.label.contains(query, ignoreCase = true) || it.key.pkg.contains(query, ignoreCase = true) }
        }
        adapter.notifyDataSetChanged()
    }

    /** Track turned on or off. Tracking an exempt app first removes the exemption (FR-004). */
    private fun onTrackChanged(row: Row, checked: Boolean) {
        if (!checked) {
            repo.untrack(row.key)
            refreshRows()
            return
        }
        when (repo.requestTrack(row.key)) {
            ExemptRules.TrackRequest.TRACK -> Unit
            ExemptRules.TrackRequest.REMOVE_EXEMPTION_THEN_TRACK ->
                showToast(getString(R.string.calm_no_longer_exempt, row.label))
            ExemptRules.TrackRequest.REFUSE_ALWAYS_EXEMPT ->
                showToast(getString(R.string.calm_always_exempt))
            // Removing a default exemption needs an explicit confirmation (spec FR-021).
            ExemptRules.TrackRequest.CONFIRM_REMOVE_DEFAULT -> AlertDialog.Builder(this)
                .setTitle(R.string.calm_track_default_title)
                .setMessage(getString(R.string.calm_remove_default_message, row.label))
                .setPositiveButton(R.string.calm_settings_track_box) { _, _ ->
                    repo.requestTrack(row.key, confirmed = true)
                    refreshRows()
                }
                .setNegativeButton(R.string.calm_cancel, null)
                .setOnDismissListener { refreshRows() }
                .show()
        }
        refreshRows()
    }

    /** Exempt turned on or off. Making an app exempt stops tracking it (FR-004). */
    private fun onExemptChanged(row: Row, checked: Boolean) {
        val pkg = row.key.pkg
        if (checked) {
            repo.addExempt(pkg)
            refreshRows()
            return
        }
        val existing = repo.exemptRows[pkg]
        when {
            existing == null -> Unit
            existing.category == ExemptCategory.SYSTEM -> showToast(getString(R.string.calm_always_exempt))
            // Removing a default exemption needs an explicit confirmation (spec FR-021).
            existing.source == ExemptSource.DEFAULT -> AlertDialog.Builder(this)
                .setTitle(R.string.calm_remove_default_title)
                .setMessage(getString(R.string.calm_remove_default_message, row.label))
                .setPositiveButton(R.string.calm_remove) { _, _ -> repo.removeExempt(pkg) }
                .setNegativeButton(R.string.calm_cancel, null)
                .setOnDismissListener { refreshRows() }
                .show()
            else -> repo.removeExempt(pkg)
        }
        refreshRows()
    }

    /** Rows always show what the repository holds, so a refused or cancelled change springs back. */
    private fun refreshRows() {
        b.calmList.post { adapter.notifyDataSetChanged() }
    }

    /** Tap on a tracked app: its own session lengths and cooldown. */
    private fun editTrackedApp(row: Row) {
        val tracked = repo.tracked[row.key] ?: return
        val eb = CalmAppEditorBinding.inflate(layoutInflater)
        eb.editorTitle.text = row.label
        var presets = tracked.presetsMin
        var cooldown = tracked.cooldownMin
        renderPresetChips(eb.editorPresets, presets) { presets = it }
        bindStepper(eb.editorCooldown, R.string.calm_settings_cooldown, null, SettingSteps.COOLDOWN_MIN, cooldown, ::formatCooldown) {
            cooldown = it
        }
        AlertDialog.Builder(this)
            .setView(eb.root)
            .setPositiveButton(R.string.calm_settings_save) { _, _ ->
                repo.saveTracked(TrackedApp.of(row.key, presets, cooldown))
                refreshRows()
            }
            .setNeutralButton(R.string.calm_use_defaults) { _, _ ->
                repo.saveTracked(TrackedApp.of(row.key, repo.settings.defaultPresets, repo.settings.defaultCooldownMin))
                refreshRows()
            }
            .setNegativeButton(R.string.calm_cancel, null)
            .show()
    }

    /** What applies to an app, under its name; null when nothing does. */
    private fun statusOf(row: Row, tracked: TrackedApp?, exempt: Boolean): String? = when {
        tracked != null -> getString(
            R.string.calm_row_status_tracked,
            tracked.presetsMin.joinToString(", "),
            if (tracked.cooldownMin == 0) getString(R.string.calm_row_no_cooldown) else getString(R.string.calm_row_cooldown, tracked.cooldownMin),
        )
        exempt -> {
            val entry = repo.exemptRows[row.key.pkg]
            getString(
                when {
                    entry?.category == ExemptCategory.SYSTEM -> R.string.calm_row_status_system
                    entry?.source == ExemptSource.DEFAULT -> R.string.calm_row_status_default
                    else -> R.string.calm_row_status_exempt
                },
            )
        }
        else -> null
    }

    // -------------------------------------------------------------------------------------- helpers

    private fun toggle(text: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(this, null, 0, R.style.CalmToggle).apply {
            this.text = text
            isSelected = selected
            setOnClickListener { failOpen("ScreenTimeSettings.chip") { onClick() } }
        }

    @ColorInt
    private fun themeColor(@AttrRes attr: Int): Int {
        val a = theme.obtainStyledAttributes(intArrayOf(attr))
        try {
            return a.getColor(0, 0)
        } finally {
            a.recycle()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

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
            rb.calmRowIcon.setImageDrawable(icons[row.key])
            val tracked = repo.tracked[row.key]
            val exempt = repo.isExempt(row.key.pkg)
            rb.calmRowTrack.isSelected = tracked != null
            rb.calmRowExempt.isSelected = exempt
            val status = statusOf(row, tracked, exempt)
            rb.calmRowStatus.text = status
            rb.calmRowStatus.isVisible = status != null
            rb.calmRowTrack.setOnClickListener {
                failOpen("ScreenTimeSettings.track") { onTrackChanged(row, checked = !rb.calmRowTrack.isSelected) }
            }
            rb.calmRowExempt.setOnClickListener {
                failOpen("ScreenTimeSettings.exempt") { onExemptChanged(row, checked = !rb.calmRowExempt.isSelected) }
            }
            if (tracked != null) {
                rb.root.setOnClickListener { failOpen("ScreenTimeSettings.edit") { editTrackedApp(row) } }
            } else {
                rb.root.setOnClickListener(null)
                rb.root.isClickable = false
            }
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

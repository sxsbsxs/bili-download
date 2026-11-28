package com.sxsbsxs.bilivdl.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.sxsbsxs.bilivdl.ui.databinding.FragmentProfileBinding

/** BiliVdl profile and download preferences, backed by the host app's settings store. */
class ProfileFragment : Fragment(R.layout.fragment_profile) {
    private var binding: FragmentProfileBinding? = null

    private val host: BiliVdlUiHost
        get() = requireActivity() as? BiliVdlUiHost
            ?: error("Host Activity must implement BiliVdlUiHost")

    private val qualityOptions = arrayOf("自动（最高可用）", "1080P", "720P", "480P", "360P")
    private val concurrencyOptions = arrayOf("1 个", "2 个", "3 个", "5 个")

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentProfileBinding.bind(view)
        binding?.accountCard?.setOnClickListener {
            if (binding?.accountLogin?.visibility == View.VISIBLE) host.requestLogin()
        }
        binding?.accountLogin?.setOnClickListener { host.requestLogin() }
        binding?.accountRefresh?.setOnClickListener { refreshAccount() }
        binding?.accountLogout?.setOnClickListener { host.requestLogout() }
        binding?.settingQualityValue?.setOnClickListener { chooseSetting(
            "优先画质",
            qualityOptions,
            currentQualityIndex
        ) { host.updateSetting("quality", it) } }
        binding?.settingConcurrencyValue?.setOnClickListener { chooseSetting(
            "同时下载数",
            concurrencyOptions,
            currentConcurrencyIndex
        ) { host.updateSetting("concurrent", it) } }
        binding?.settingClearCache?.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("清理临时缓存")
                .setMessage("仅清除临时下载缓存，不会删除已经下载完成的视频。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清理") { _, _ -> host.clearTemporaryCache() }
                .show()
        }
        binding?.settingRepository?.setOnClickListener { host.openRepository() }
        binding?.settingCheckUpdates?.setOnClickListener { host.checkForUpdates() }
        bindSwitches()
        refreshAccount()
        refreshSettings()
    }

    private var currentQualityIndex = 0
    private var currentConcurrencyIndex = 1

    private fun bindSwitches() {
        binding?.settingWifiOnly?.setOnCheckedChangeListener { _, checked ->
            host.updateSetting("wifi_only", checked)
        }
        binding?.settingDanmaku?.setOnCheckedChangeListener { _, checked ->
            host.updateSetting("danmaku", checked)
        }
        binding?.settingSubtitles?.setOnCheckedChangeListener { _, checked ->
            host.updateSetting("subtitles", checked)
        }
        binding?.settingAudioOnly?.setOnCheckedChangeListener { _, checked ->
            host.updateSetting("audio_only", checked)
        }
    }

    private fun chooseSetting(
        title: String,
        options: Array<String>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit
    ) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(options, selectedIndex.coerceIn(options.indices)) { dialog, which ->
                onSelected(which)
                dialog.dismiss()
                refreshSettings()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshSettings() {
        host.loadSettings { settings ->
            val b = binding ?: return@loadSettings
            currentQualityIndex = settings.qualityIndex.coerceIn(qualityOptions.indices)
            currentConcurrencyIndex =
                settings.concurrencyIndex.coerceIn(concurrencyOptions.indices)
            b.settingQualityValue.text = qualityOptions[currentQualityIndex]
            b.settingConcurrencyValue.text = concurrencyOptions[currentConcurrencyIndex]
            b.settingDownloadPath.text = settings.downloadPath

            // Prevent loading stored values from firing preference writes.
            b.settingWifiOnly.setOnCheckedChangeListener(null)
            b.settingDanmaku.setOnCheckedChangeListener(null)
            b.settingSubtitles.setOnCheckedChangeListener(null)
            b.settingAudioOnly.setOnCheckedChangeListener(null)
            b.settingWifiOnly.isChecked = settings.wifiOnly
            b.settingDanmaku.isChecked = settings.downloadDanmaku
            b.settingSubtitles.isChecked = settings.downloadSubtitles
            b.settingAudioOnly.isChecked = settings.audioOnly
            bindSwitches()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAccount()
        refreshSettings()
    }

    fun refreshAccount() {
        host.loadAccount { account ->
            val b = binding ?: return@loadAccount
            b.accountName.text = account.name
            b.accountStatus.text = account.status
            b.accountLogin.visibility = if (account.loggedIn) View.GONE else View.VISIBLE
            b.accountLogout.visibility = if (account.loggedIn) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}

package com.sxsbsxs.bilivdl.ui

import android.content.Context
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import com.sxsbsxs.bilivdl.ui.databinding.FragmentSearchBinding

class SearchController(
    private val fragment: DiscoverFragment,
    private val binding: FragmentSearchBinding
) {
    private lateinit var adapter: VideoAdapter
    private var requestToken = 0

    private val host: BiliVdlUiHost
        get() = fragment.requireActivity() as? BiliVdlUiHost
            ?: error("Host Activity must implement BiliVdlUiHost")

    fun bind() {
        adapter = VideoAdapter(host::openVideo)
        binding.searchResults.layoutManager = GridLayoutManager(fragment.requireContext(), 2)
        binding.searchResults.adapter = adapter
        binding.searchResults.setHasFixedSize(true)
        binding.searchButton.setOnClickListener { search() }
        binding.searchInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                search()
                true
            } else false
        }
    }

    private fun search() {
        val query = binding.searchInput.text?.toString()?.trim().orEmpty()
        if (query.isBlank()) {
            binding.searchInputLayout.error = "请输入搜索内容"
            return
        }
        binding.searchInputLayout.error = null
        (fragment.requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
        binding.searchProgress.visibility = View.VISIBLE
        binding.searchButton.isEnabled = false
        binding.searchStatus.text = "正在搜索…"
        val token = ++requestToken
        host.searchVideos(query, 1) { outcome ->
            if (!fragment.isAdded || token != requestToken) return@searchVideos
            outcome.fold(
                onSuccess = { videos ->
                    adapter.submitList(videos)
                    binding.searchStatus.text =
                        if (videos.isEmpty()) "没有找到相关视频" else "找到 " + videos.size + " 个视频"
                },
                onFailure = { error ->
                    binding.searchStatus.text = "搜索失败：" + (error.message ?: "网络连接异常")
                    Toast.makeText(fragment.requireContext(), "请检查网络后重试", Toast.LENGTH_SHORT).show()
                }
            )
            binding.searchProgress.visibility = View.GONE
            binding.searchButton.isEnabled = true
        }
    }
}

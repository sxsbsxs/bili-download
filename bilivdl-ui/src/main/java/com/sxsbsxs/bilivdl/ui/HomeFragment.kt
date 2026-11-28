package com.sxsbsxs.bilivdl.ui

import android.os.Bundle
import android.view.View
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.sxsbsxs.bilivdl.ui.databinding.FragmentHomeBinding

/** Original BiliVdl recommendation screen; data comes from the host repository. */
class HomeFragment : Fragment(R.layout.fragment_home) {
    private var binding: FragmentHomeBinding? = null
    private var page = 0
    private var loading = false
    private var hasMore = true
    private var duplicatePages = 0
    private var requestToken = 0
    private val videos = mutableListOf<BiliVdlVideo>()
    private lateinit var adapter: VideoAdapter

    private val host: BiliVdlUiHost
        get() = requireActivity() as? BiliVdlUiHost
            ?: error("Host Activity must implement BiliVdlUiHost")

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = FragmentHomeBinding.bind(view)
        binding = b
        adapter = VideoAdapter(host::openVideo)
        b.homeRecommendations.layoutManager = LinearLayoutManager(requireContext())
        b.homeRecommendations.adapter = adapter
        b.homeSearchButton.setOnClickListener { host.navigateToDiscover() }
        b.homeSearchEntry.setOnClickListener { host.navigateToDiscover() }
        b.homeSwipe.setOnRefreshListener { refresh() }
        b.homeScroll.setOnScrollChangeListener(
            NestedScrollView.OnScrollChangeListener { scroll, _, y, _, _ ->
                val child = scroll.getChildAt(0) ?: return@OnScrollChangeListener
                if (!loading && hasMore && y + scroll.height >= child.measuredHeight - 700) {
                    loadPage(page + 1)
                }
            }
        )
        refresh()
    }

    private fun refresh() {
        val b = binding ?: return
        requestToken++
        page = 0
        hasMore = true
        duplicatePages = 0
        loading = false
        videos.clear()
        adapter.submitList(emptyList())
        b.homeSwipe.isRefreshing = true
        loadPage(1)
    }

    private fun loadPage(target: Int) {
        val b = binding ?: return
        if (loading || (!hasMore && target > 1)) return
        loading = true
        val token = ++requestToken
        if (target == 1 && !b.homeSwipe.isRefreshing) b.homeProgress.visibility = View.VISIBLE
        host.loadPopular(target) { outcome ->
            if (token != requestToken || binding == null) return@loadPopular
            outcome.fold(
                onSuccess = { result ->
                    val known = videos.mapTo(hashSetOf()) { it.bvid }
                    val fresh = result.filter { it.bvid.isNotBlank() && known.add(it.bvid) }
                    videos.addAll(fresh)
                    page = target
                    duplicatePages = if (fresh.isEmpty() && result.size >= 20) duplicatePages + 1 else 0
                    hasMore = result.size >= 20 && duplicatePages < 3
                    adapter.submitList(videos.toList())
                    b.homeFeedStatus.text = when {
                        videos.isEmpty() -> "暂时没有推荐视频 · 下拉重试"
                        hasMore -> "已加载 " + videos.size + " 条 · 下滑继续加载"
                        else -> "已加载全部推荐 · 下拉刷新换一批"
                    }
                    b.homeRecommendations.post {
                        val live = binding ?: return@post
                        val needsMore = fresh.isEmpty() || !live.homeScroll.canScrollVertically(1)
                        if (!loading && hasMore && needsMore) loadPage(page + 1)
                    }
                },
                onFailure = { error ->
                    b.homeFeedStatus.text = "加载失败：" + (error.message ?: "网络异常") + " · 下拉重试"
                }
            )
            b.homeProgress.visibility = View.GONE
            b.homeSwipe.isRefreshing = false
            loading = false
        }
    }

    override fun onDestroyView() {
        requestToken++
        binding = null
        loading = false
        super.onDestroyView()
    }
}

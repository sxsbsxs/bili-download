package com.sxsbsxs.bilivdl.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.sxsbsxs.bilivdl.ui.databinding.FragmentSearchBinding

class DiscoverFragment : Fragment(R.layout.fragment_search) {
    private var binding: FragmentSearchBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentSearchBinding.bind(view)
        SearchController(this, checkNotNull(binding)).bind()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}

package com.sxsbsxs.bilivdl.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.sxsbsxs.bilivdl.ui.databinding.FragmentProfileBinding

/** Original BiliVdl profile screen; session credentials remain owned by the host app. */
class ProfileFragment : Fragment(R.layout.fragment_profile) {
    private var binding: FragmentProfileBinding? = null

    private val host: BiliVdlUiHost
        get() = requireActivity() as? BiliVdlUiHost
            ?: error("Host Activity must implement BiliVdlUiHost")

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentProfileBinding.bind(view)
        binding?.accountLogin?.setOnClickListener { host.requestLogin() }
        binding?.accountRefresh?.setOnClickListener { refreshAccount() }
        binding?.accountLogout?.setOnClickListener { host.requestLogout(); refreshAccount() }
        refreshAccount()
    }

    override fun onResume() {
        super.onResume()
        refreshAccount()
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

package com.sxsbsxs.bilivdl.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import com.sxsbsxs.bilivdl.ui.databinding.DialogOnboardingBinding

/** A lightweight five-step introduction shown only until the user finishes it. */
class OnboardingDialogFragment : DialogFragment(R.layout.dialog_onboarding) {
    private var binding: DialogOnboardingBinding? = null
    private var currentPage = 0

    private val pageIcons = intArrayOf(
        R.drawable.ic_bilidl_brand,
        R.drawable.ic_home,
        R.drawable.ic_search,
        R.drawable.ic_download,
        R.drawable.ic_profile
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = false
        currentPage = savedInstanceState?.getInt(STATE_PAGE) ?: 0
    }

    override fun onStart() {
        super.onStart()
        dialog?.setCanceledOnTouchOutside(false)
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            setDimAmount(0.72f)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = DialogOnboardingBinding.bind(view)
        binding = b
        b.onboardingNext.setOnClickListener {
            if (currentPage < pageIcons.lastIndex) {
                currentPage++
                renderPage()
            } else {
                requireContext()
                    .getSharedPreferences(PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_COMPLETED, true)
                    .apply()
                dismiss()
            }
        }
        renderPage()
    }

    private fun renderPage() {
        val b = binding ?: return
        val titles = resources.getStringArray(R.array.onboarding_titles)
        val descriptions = resources.getStringArray(R.array.onboarding_descriptions)
        val pageNumber = currentPage + 1

        b.onboardingIcon.setImageResource(pageIcons[currentPage])
        b.onboardingTitle.text = titles[currentPage]
        b.onboardingDescription.text = descriptions[currentPage]
        b.onboardingStep.text = getString(
            R.string.onboarding_step_format,
            pageNumber,
            pageIcons.size
        )
        b.onboardingProgress.progress = pageNumber * 100 / pageIcons.size
        b.onboardingNext.setText(
            if (currentPage == pageIcons.lastIndex) {
                R.string.onboarding_finish
            } else {
                R.string.onboarding_next
            }
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_PAGE, currentPage)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "bilidl-first-run-onboarding"
        const val PREFERENCES_NAME = "ui"
        const val KEY_COMPLETED = "onboarding_completed"
        private const val STATE_PAGE = "onboarding_page"
    }
}

package com.vuforia.engine.native_sample

import android.animation.ObjectAnimator
import android.content.DialogInterface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.fragment.app.DialogFragment

class InfoPopupDialogFragment : DialogFragment() {

    var onOpenSite: (() -> Unit)? = null
    var onClosed: (() -> Unit)? = null

    private var shimmerAnimator: ObjectAnimator? = null
    private var isDismissing = false
    private var closedNotified = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_info_popup, container, false)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawableResource(android.R.color.transparent)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        isCancelable = true
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val root = view.findViewById<FrameLayout>(R.id.info_popup_root)
        val card = view.findViewById<View>(R.id.info_popup_card)
        val shimmer = view.findViewById<View>(R.id.info_popup_shimmer)
        val link = view.findViewById<TextView>(R.id.info_popup_link)
        val gotIt = view.findViewById<Button>(R.id.info_popup_got_it)

        applyGradientBackground(card)

        root.setOnClickListener { dismissWithAnimation(card, root) }
        card.setOnClickListener { /* keep outside-tap dismiss */ }
        gotIt.setOnClickListener { dismissWithAnimation(card, root) }
        link.setOnClickListener {
            onOpenSite?.invoke()
            dismissWithAnimation(card, root)
        }

        startEnterAnimation(card, root)
        card.doOnLayout { startShimmer(shimmer, card.width) }
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        notifyClosed()
    }

    private fun applyGradientBackground(view: View) {
        val colors = intArrayOf(
            0xFFF97316.toInt(),
            0xFFFACC15.toInt(),
            0xFFEC4899.toInt(),
            0xFF0EA5E9.toInt()
        )
        val gradient = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
        gradient.setColors(colors, floatArrayOf(0.0f, 0.35f, 0.65f, 1.0f))
        gradient.cornerRadius = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 28f, resources.displayMetrics
        )
        view.background = gradient
        view.clipToOutline = true
    }

    private fun startEnterAnimation(card: View, root: View) {
        root.alpha = 0f
        card.alpha = 0f
        card.translationY = 50f

        root.animate()
            .alpha(1f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        card.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(320L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun dismissWithAnimation(card: View, root: View) {
        if (isDismissing) return
        isDismissing = true

        root.animate()
            .alpha(0f)
            .setDuration(180L)
            .start()

        card.animate()
            .alpha(0f)
            .translationY(50f)
            .setDuration(180L)
            .withEndAction {
                if (isAdded) dismissAllowingStateLoss()
            }
            .start()

        view?.performHapticFeedback(HapticFeedbackConstants.REJECT)
    }

    private fun startShimmer(shimmer: View, cardWidth: Int) {
        shimmer.translationX = -cardWidth * 1.5f
        shimmerAnimator?.cancel()
        shimmerAnimator = ObjectAnimator.ofFloat(
            shimmer,
            View.TRANSLATION_X,
            -cardWidth * 1.5f,
            cardWidth * 1.5f
        ).apply {
            duration = 3500L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun notifyClosed() {
        if (closedNotified) return
        closedNotified = true
        onClosed?.invoke()
    }

    override fun onDestroyView() {
        shimmerAnimator?.cancel()
        shimmerAnimator = null
        super.onDestroyView()
    }
}

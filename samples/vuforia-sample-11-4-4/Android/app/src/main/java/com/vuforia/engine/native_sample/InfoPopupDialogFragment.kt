package com.vuforia.engine.native_sample

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.TypedValue
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.fragment.app.DialogFragment

class InfoPopupDialogFragment : DialogFragment() {

    private var shimmerAnimator: ObjectAnimator? = null
    private var isDismissing = false

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
        val message = view.findViewById<TextView>(R.id.info_popup_message)

        applyGradientBackground(card)
        configureMessage(message)

        root.setOnClickListener { dismissWithAnimation(card, root) }
        card.setOnClickListener { /* consume clicks so outside tap dismiss works */ }

        startEnterAnimation(card, root)

        card.doOnLayout {
            startShimmer(shimmer, card.width)
        }

        // Medium-ish haptic on show
        view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
    }

    private fun applyGradientBackground(view: View) {
        val colors = intArrayOf(
            0xFFF97316.toInt(), // Orange
            0xFFFACC15.toInt(), // Yellow
            0xFFEC4899.toInt(), // Pink
            0xFF0EA5E9.toInt()  // Blue
        )
        val stops = floatArrayOf(0.0f, 0.35f, 0.65f, 1.0f)

        val gradient = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
        // setColors(colors, stops) is available on API 29+
        gradient.setColors(colors, stops)
        gradient.cornerRadius = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 25f, resources.displayMetrics
        )
        gradient.setStroke(
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.5f, resources.displayMetrics).toInt(),
            0xB30EA5E9.toInt() // 70% opacity of glimp_blue
        )
        view.background = gradient
    }

    private fun configureMessage(message: TextView) {
        val text = SpannableStringBuilder()
        text.append("Scan Your Glimp And\n")
        text.append("The Magic Begins!\n\n")
        val prefix = "Create a new Glimp on "
        text.append(prefix)
        val linkText = "glimp.app"
        val linkStart = text.length
        text.append(linkText)

        val linkSpan = object : ClickableSpan() {
            override fun onClick(widget: View) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://glimp.app")))
            }

            override fun updateDrawState(ds: TextPaint) {
                ds.isUnderlineText = true
                ds.color = 0xFF66CCFF.toInt()
            }
        }

        text.setSpan(linkSpan, linkStart, linkStart + linkText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        message.text = text
        message.movementMethod = LinkMovementMethod.getInstance()
        message.highlightColor = android.graphics.Color.TRANSPARENT
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

        view?.performHapticFeedback(android.view.HapticFeedbackConstants.REJECT)
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

    override fun onDestroyView() {
        shimmerAnimator?.cancel()
        shimmerAnimator = null
        super.onDestroyView()
    }
}

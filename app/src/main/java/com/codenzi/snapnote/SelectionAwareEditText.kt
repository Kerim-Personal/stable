package com.codenzi.snapnote

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.textfield.TextInputEditText

class SelectionAwareEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.editTextStyle
) : TextInputEditText(context, attrs, defStyleAttr) {

    private var selectionChangedListener: ((Int, Int) -> Unit)? = null

    fun setOnSelectionChangedListener(listener: (Int, Int) -> Unit) {
        this.selectionChangedListener = listener
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        selectionChangedListener?.invoke(selStart, selEnd)
    }
}
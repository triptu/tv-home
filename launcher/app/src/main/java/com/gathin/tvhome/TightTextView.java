package com.gathin.tvhome;

import android.content.Context;
import android.text.Layout;
import android.util.AttributeSet;
import android.widget.TextView;

/**
 * A wrap_content TextView as wide as its longest line. A plain TextView keeps the width it measured before
 * balanced line breaking shortened the lines, which leaves an empty strip on the right of a panel.
 */
public class TightTextView extends TextView {

    public TightTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        Layout layout = getLayout();
        if (layout == null || layout.getLineCount() < 2
                || MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) return;
        float widest = 0;
        for (int i = 0; i < layout.getLineCount(); i++) widest = Math.max(widest, layout.getLineWidth(i));
        int width = (int) Math.ceil(widest) + getCompoundPaddingLeft() + getCompoundPaddingRight();
        if (width < getMeasuredWidth()) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec);
        }
    }
}

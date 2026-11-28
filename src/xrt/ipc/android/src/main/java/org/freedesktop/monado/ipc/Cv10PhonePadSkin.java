// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.ipc;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.Button;

final class Cv10PhonePadSkin {
    static final int BACKGROUND = 0xff0d1016, TEXT = 0xffeef2f9, HINT = 0xffb8c2d2;

    private Cv10PhonePadSkin() {}

    static int dp(View v, int dp) {
        return Math.round(v.getResources().getDisplayMetrics().density * dp);
    }

    static GradientDrawable shell(View v, int fill, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(v, radius));
        d.setStroke(dp(v, 1), 0xff46505c);
        return d;
    }

    static void style(Button b) {
        int left = b.getPaddingLeft(), top = b.getPaddingTop();
        int right = b.getPaddingRight(), bottom = b.getPaddingBottom();
        b.setTextColor(TEXT);
        b.setBackgroundTintList(null);
        RippleDrawable ripple = new RippleDrawable(ColorStateList.valueOf(0xff526885),
                shell(b, 0xff222a35, 16), null);
        b.setBackground(new InsetDrawable(ripple, dp(b, 2)));
        b.setPadding(left, top, right, bottom);
    }
}

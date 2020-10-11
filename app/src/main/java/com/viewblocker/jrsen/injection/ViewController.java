package com.viewblocker.jrsen.injection;

import android.app.Activity;
import android.util.Pair;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.View;
import android.view.ViewGroup;

import com.viewblocker.jrsen.injection.util.Logger;
import com.viewblocker.jrsen.rule.ViewRule;
import com.viewblocker.jrsen.util.Preconditions;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.viewblocker.jrsen.BlockerApplication.TAG;

/**
 * Created by jrsen on 17-10-15.
 */

public final class ViewController {

    private static final SparseIntArray ruleHashCache = new SparseIntArray();
    private static final SparseArray<Pair<WeakReference<View>, ViewProperty>> blockedViewCache = new SparseArray<>();

    static final View.OnAttachStateChangeListener ATTACH_STATE_CHANGE_LISTENER = new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(View v) {
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
            v.removeOnAttachStateChangeListener(this);
            int viewCodeHash = v.hashCode();
            blockedViewCache.delete(viewCodeHash);
            ruleHashCache.delete(ruleHashCache.indexOfValue(viewCodeHash));
        }
    };

    public static void applyRuleBatch(Activity activity, List<ViewRule> rules) {
        Logger.d(TAG, "[ApplyRuleBatch info start------------------------------------]");
        for (ViewRule rule : new ArrayList<>(rules)) {
            try {
                Logger.d(TAG, "[Apply rule]:" + rule.toString());
                int ruleHashCode = rule.hashCode();
                int viewHashCode = ruleHashCache.get(ruleHashCode);
                Pair<WeakReference<View>, ViewProperty> viewInfo = blockedViewCache.get(viewHashCode);
                View view = viewInfo != null ? viewInfo.first.get() : null;
                if (view == null) {
                    ruleHashCache.delete(ruleHashCode);
                    blockedViewCache.delete(viewHashCode);
                    view = ViewHelper.findViewBestMatch(activity, rule);
                    Preconditions.checkNotNull(view, "apply rule fail not match any view");
                }
                boolean blocked = applyRule(view, rule);
                if (blocked) {
                    view.addOnAttachStateChangeListener(ATTACH_STATE_CHANGE_LISTENER);
                    Logger.i(TAG, String.format("[Success] %s#%s has been blocked", activity, view));
                } else {
                    Logger.i(TAG, String.format("[Skipped] %s#%s already be blocked", activity, view));
                }
            } catch (NullPointerException e) {
                Logger.w(TAG, String.format("[Failed] %s#%s block failed because %s", activity, rule.viewClass, e.getMessage()));
            }
        }
        Logger.d(TAG, "[ApplyRuleBatch info end------------------------------------]");
    }

    public static boolean applyRule(View v, ViewRule viewRule) {
        int ruleHashCode = viewRule.hashCode();
        int viewHashCode = ruleHashCache.get(ruleHashCode);
        Pair<WeakReference<View>, ViewProperty> viewInfo = blockedViewCache.get(viewHashCode);
        View blockedView = viewInfo != null ? viewInfo.first.get() : null;
        if (blockedView == v && v.getVisibility() == viewRule.visibility) {
            return false;
        }
        ViewProperty viewProperty = blockedView == v ? viewInfo.second : ViewProperty.create(v);
        v.setAlpha(0f);
        v.setClickable(false);
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp != null) {
            switch (viewRule.visibility) {
                case View.GONE:
                    lp.width = 0;
                    lp.height = 0;
                    break;
                case View.INVISIBLE:
                    lp.width = viewProperty.layout_params_width;
                    lp.height = viewProperty.layout_params_height;
                    break;
            }
            v.requestLayout();
        }
        v.setVisibility(viewRule.visibility);
        viewHashCode = v.hashCode();
        ruleHashCache.put(ruleHashCode, viewHashCode);
        blockedViewCache.put(viewHashCode, Pair.create(new WeakReference<>(v), viewProperty));
        Logger.d(TAG, String.format(Locale.getDefault(), "apply rule add view cache %d=%s", ruleHashCode, v));
        return true;
    }

    public static void revokeRuleBatch(Activity activity, List<ViewRule> rules) {
        for (ViewRule rule : new ArrayList<>(rules)) {
            try {
                Logger.d(TAG, "revoke rule:" + rule.toString());
                int ruleHashCode = rule.hashCode();
                int viewHashCode = ruleHashCache.get(ruleHashCode);
                Pair<WeakReference<View>, ViewProperty> viewInfo = blockedViewCache.get(viewHashCode);
                View view = viewInfo != null ? viewInfo.first.get() : null;
                if (view == null) {
                    Logger.w(TAG, "view cache not found");
                    ruleHashCache.delete(ruleHashCode);
                    blockedViewCache.delete(viewHashCode);
                    view = ViewHelper.findViewBestMatch(activity, rule);
                    Logger.w(TAG, "find view in activity" + view);
                    Preconditions.checkNotNull(view, "revoke rule fail can't found block view");
                }
                revokeRule(view, rule);
                Logger.i(TAG, String.format("###revoke rule success [Act]:%s  [View]:%s", activity, view));
            } catch (NullPointerException e) {
                Logger.w(TAG, String.format("###revoke rule fail [Act]:%s  [View]:%s [Reason]:%s", activity, null, e.getMessage()));
            }
        }
    }

    public static void revokeRule(View v, ViewRule viewRule) {
        int ruleHashCode = viewRule.hashCode();
        int viewHashCode = ruleHashCache.get(ruleHashCode);
        Pair<WeakReference<View>, ViewProperty> viewInfo = blockedViewCache.get(viewHashCode);
        if (viewInfo != null && viewInfo.first.get() == v) {
            ViewProperty viewProperty = viewInfo.second;
            v.setAlpha(viewProperty.alpha);
            v.setClickable(viewProperty.clickable);
            v.setVisibility(viewProperty.visibility);
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null) {
                lp.width = viewProperty.layout_params_width;
                lp.height = viewProperty.layout_params_height;
                v.requestLayout();
            }
            v.removeOnAttachStateChangeListener(ATTACH_STATE_CHANGE_LISTENER);
            ruleHashCache.delete(ruleHashCode);
            blockedViewCache.remove(viewHashCode);
            Logger.d(TAG, String.format(Locale.getDefault(), "revoke blocked view %d=%s %s", ruleHashCode, v, viewProperty));
        } else {
            // cache missing why?
            Logger.w(TAG, "view cache missing why?");
            v.setAlpha(1f);
            v.setVisibility(viewRule.visibility);
        }
    }

    private static final class ViewProperty {

        final float alpha;
        final boolean clickable;
        final int visibility;
        final int layout_params_width;
        final int layout_params_height;

        public ViewProperty(float alpha, boolean clickable, int visibility, int layout_params_width, int layout_params_height) {
            this.alpha = alpha;
            this.clickable = clickable;
            this.visibility = visibility;
            this.layout_params_width = layout_params_width;
            this.layout_params_height = layout_params_height;
        }

        @Override
        public String toString() {
            final StringBuffer sb = new StringBuffer("ViewProperty{");
            sb.append("alpha=").append(alpha);
            sb.append(", clickable=").append(clickable);
            sb.append(", visibility=").append(visibility);
            sb.append(", layout_params_width=").append(layout_params_width);
            sb.append(", layout_params_height=").append(layout_params_height);
            sb.append('}');
            return sb.toString();
        }

        public static ViewProperty create(View view) {
            float alpha = view.getAlpha();
            boolean clickable = view.isClickable();
            int visibility = view.getVisibility();
            ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
            int width = layoutParams != null ? layoutParams.width : 0;
            int height = layoutParams != null ? layoutParams.height : 1;
            return new ViewProperty(alpha, clickable, visibility, width, height);
        }
    }

}

package dev.peanuts.online;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import dev.nxnative.android.R;
import dev.peanuts.ui.Feel;
import dev.peanuts.ui.PUi;

/**
 * Home's Nextendo button, at the top right beside Screens off, FAQ and Settings (MainActivity's header buttons), and the friends page it opens.
 *
 * Signed in (Settings › Online), the button is the account's Nextendo picture with its name under it; signed out it is a globe in the header buttons' style,
 * which starts the sign-in (or opens Settings when this build has no Nextendo application). The picture opens the friends page: the player's name and friend
 * code (Copy puts it on the clipboard), a friend code field to send a request, requests received (Accept, Decline) and sent, and the friend list with what each
 * friend is playing now, Remove on each. The page reads the list again every 30 s while it is open. Everything comes from {@link NextendoApi}.
 */
public final class NextendoHome {
    private NextendoHome() {}

    /** The header button; openSettings shows Settings (MainActivity's page) */
    public static View button(Activity a, Runnable openSettings) {
        LinearLayout column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        fill(a, column, openSettings);
        // a sign-in or out in Settings, a new picture: the button follows when Home has the focus again
        column.getViewTreeObserver().addOnWindowFocusChangeListener(has -> { if (has && column.isAttachedToWindow()) fill(a, column, openSettings); });
        return column;
    }

    private static void fill(Activity a, LinearLayout column, Runnable openSettings) {
        column.removeAllViews();
        String user = Nextendo.username(a);
        View face;
        String caption;
        if (user == null) {
            caption = "Online";
            face = PUi.iconButton(a, R.drawable.ic_globe, "Nextendo Network: sign in to play online", PUi.Kind.TONAL, Feel.button(v -> {
                if (!Nextendo.configured()) { openSettings.run(); return; }
                if (Nextendo.working()) { Toast.makeText(a, "Waiting for Nextendo…", Toast.LENGTH_SHORT).show(); return; }
                Toast.makeText(a, "Sign in on the Nextendo page, then come back to Peanuts Native", Toast.LENGTH_LONG).show();
                Nextendo.signIn(a, why -> {
                    Toast.makeText(a, why == null ? "Signed in to Nextendo as " + Nextendo.username(a) : "Not signed in: " + why, Toast.LENGTH_LONG).show();
                    if (column.isAttachedToWindow()) fill(a, column, openSettings);
                });
            }));
        } else {
            NextendoApi.Me me = NextendoApi.cachedMe(a);
            caption = me != null ? me.shown() : user.isEmpty() ? "Nextendo" : user;
            FrameLayout box = face(a, caption, me == null ? 0 : me.pid, 44, 14);
            box.setForeground(PUi.ripple(a, PUi.round(0, PUi.dp(a, 14), 0, 0)));
            box.setFocusable(true);
            box.setClickable(true);
            box.setContentDescription(caption + " on Nextendo Network. Friends and friend code");
            box.setOnClickListener(Feel.button(v -> friends(a)));
            face = box;
            if (me == null) NextendoApi.me(a, m -> { if (m != null && column.isAttachedToWindow()) fill(a, column, openSettings); });   // the first time: name and picture
        }
        column.addView(face);
        TextView t = PUi.text(a, caption, 10, R.color.bn_accent, 700);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setMaxWidth(PUi.dp(a, 64));
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, PUi.dp(a, 2), 0, 0);
        t.setMinHeight(PUi.dp(a, 16));
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        t.setOnClickListener(v -> face.performClick());
        column.addView(t, PUi.wrap());
    }

    /** A rounded picture of sizeDp: the name's first letter on the leaf colour until the picture arrives (pid 0: the letter only) */
    static FrameLayout face(Context c, String name, long pid, int sizeDp, int radiusDp) {
        FrameLayout f = new FrameLayout(c);
        int r = PUi.dp(c, radiusDp);
        f.setBackground(PUi.round(PUi.col(c, R.color.bn_leaf), r, 0, 0));
        f.setOutlineProvider(new ViewOutlineProvider() { @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); } });
        f.setClipToOutline(true);
        f.setLayoutParams(new LinearLayout.LayoutParams(PUi.dp(c, sizeDp), PUi.dp(c, sizeDp)));
        String n = name == null ? "" : name.trim();
        TextView letter = PUi.text(c, n.isEmpty() ? "?" : n.substring(0, n.offsetByCodePoints(0, 1)).toUpperCase(java.util.Locale.ROOT), sizeDp * 0.42f, R.color.bn_on_leaf, 800);
        letter.setGravity(Gravity.CENTER);
        letter.setIncludeFontPadding(false);
        letter.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        f.addView(letter, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (pid != 0) {
            ImageView img = new ImageView(c);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            f.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            NextendoApi.picture(pid, null, b -> {
                if (b == null) return;
                letter.setVisibility(View.GONE);
                img.setAlpha(0f);
                img.setImageBitmap(b);
                img.animate().alpha(1f).setDuration(180).start();
            });
        }
        return f;
    }

    // ---------- the friends page ----------

    private static final long RELOAD_MS = 30_000L;

    public static void friends(Activity a) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = PUi.card(a);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = PUi.dp(a, 20);
        card.setPadding(p, p, p, p);

        // the player: picture, name, friend code with Copy; Close
        LinearLayout top = PUi.row(a);
        NextendoApi.Me cached = NextendoApi.cachedMe(a);
        String user = Nextendo.username(a);
        FrameLayout meFace = face(a, cached != null ? cached.shown() : user, cached == null ? 0 : cached.pid, 64, 18);
        top.addView(meFace);
        LinearLayout who = new LinearLayout(a);
        who.setOrientation(LinearLayout.VERTICAL);
        TextView name = PUi.text(a, cached != null ? cached.shown() : user == null ? "Nextendo" : user, 22, R.color.bn_text, 800);
        name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END);
        name.setAccessibilityHeading(true);
        who.addView(name, PUi.fill());
        TextView handle = PUi.small(a, cached != null && !cached.username.isEmpty() && !cached.username.equals(cached.shown()) ? "@" + cached.username : "Nextendo Network");
        who.addView(handle, PUi.fill());
        LinearLayout codeRow = PUi.row(a);
        TextView code = PUi.text(a, cached != null && !cached.friendCode.isEmpty() ? cached.friendCode : "Friend code…", 17, R.color.bn_text, 700);
        code.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        code.setTextIsSelectable(true);
        codeRow.addView(code, PUi.wrap());
        View copy = PUi.button(a, "Copy", PUi.Kind.TONAL, Feel.button(v -> {
            NextendoApi.Me m = NextendoApi.cachedMe(a);
            if (m == null || m.friendCode.isEmpty()) { Toast.makeText(a, "The friend code has not arrived yet", Toast.LENGTH_SHORT).show(); return; }
            ClipboardManager cb = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            cb.setPrimaryClip(ClipData.newPlainText("Nextendo friend code", m.friendCode));
            Toast.makeText(a, "Friend code copied: " + m.friendCode, Toast.LENGTH_SHORT).show();
        }));
        LinearLayout.LayoutParams cl = PUi.wrap(); cl.setMarginStart(PUi.dp(a, 10));
        codeRow.addView(copy, cl);
        LinearLayout.LayoutParams crl = PUi.fill(); crl.topMargin = PUi.dp(a, 6);
        who.addView(codeRow, crl);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wl.setMarginStart(PUi.dp(a, 14));
        top.addView(who, wl);
        top.addView(PUi.iconButton(a, R.drawable.ic_close, "Close", PUi.Kind.TONAL, Feel.button(v -> d.dismiss())));
        card.addView(top, PUi.fill());

        // add a friend
        PUi.add(card, PUi.section(a, "Add a friend"), 18);
        LinearLayout addRow = PUi.row(a);
        EditText field = new EditText(a);
        field.setHint("SW-0000-0000-0000");
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setFilters(new InputFilter[] {new InputFilter.LengthFilter(24)});
        field.setImeOptions(EditorInfo.IME_ACTION_SEND);
        addRow.addView(field, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView status = PUi.small(a, "");

        LinearLayout lists = new LinearLayout(a);
        lists.setOrientation(LinearLayout.VERTICAL);
        Runnable[] reload = new Runnable[1];

        View add = PUi.button(a, "Send request", PUi.Kind.PRIMARY, null);
        Runnable send = () -> {
            String typed = field.getText().toString();
            if (typed.trim().isEmpty()) { field.requestFocus(); return; }
            add.setEnabled(false);
            status.setText("Sending a friend request…");
            NextendoApi.addFriend(a, typed, r -> {
                add.setEnabled(true);
                if (!d.isShowing()) return;
                if (!r.ok()) { status.setText(r.error); return; }
                boolean already = r.json != null && r.json.optBoolean("already");
                JSONObjectName fn = new JSONObjectName(r.json);
                status.setText(already ? "You are already friends with " + fn.name : "Friend request sent to " + fn.name);
                field.setText("");
                reload[0].run();
            });
        };
        add.setOnClickListener(Feel.button(v -> send.run()));
        field.setOnEditorActionListener((v, id, e) -> { if (id == EditorInfo.IME_ACTION_SEND) { send.run(); return true; } return false; });
        LinearLayout.LayoutParams al = PUi.wrap(); al.setMarginStart(PUi.dp(a, 10));
        addRow.addView(add, al);
        PUi.add(card, addRow, 6);
        PUi.add(card, status, 6);
        PUi.add(card, lists, 6);

        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(false);
        sv.addView(card);
        d.setContentView(sv);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            int width = Math.min(a.getResources().getDisplayMetrics().widthPixels - PUi.dp(a, 32), PUi.dp(a, 620));
            w.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }

        // the profile, then the lists, again every 30 s while the page shows
        NextendoApi.me(a, m -> {
            if (m == null || !d.isShowing()) return;
            name.setText(m.shown());
            handle.setText(!m.username.isEmpty() && !m.username.equals(m.shown()) ? "@" + m.username : "Nextendo Network");
            code.setText(m.friendCode.isEmpty() ? "No friend code" : m.friendCode);
            if (cached == null || cached.pid != m.pid) {
                int i = top.indexOfChild(meFace);
                top.removeView(top.getChildAt(i));
                top.addView(face(a, m.shown(), m.pid, 64, 18), i);
            }
        });
        Runnable tick = new Runnable() {
            @Override public void run() {
                if (!d.isShowing()) return;
                reload[0].run();
                lists.postDelayed(this, RELOAD_MS);
            }
        };
        reload[0] = () -> NextendoApi.friends(a, f -> { if (d.isShowing()) render(a, d, lists, status, f, reload[0]); }, why -> { if (d.isShowing()) status.setText(why); });
        if (lists.getChildCount() == 0) PUi.add(lists, PUi.small(a, "Loading your friends…"), 8);
        d.setOnShowListener(x -> { lists.post(tick); copy.requestFocus(); });
        d.setOnDismissListener(x -> lists.removeCallbacks(tick));
        d.show();
    }

    /** The name a friend request's answer gives ({friend: {...}}), else "that player" */
    private static final class JSONObjectName {
        final String name;
        JSONObjectName(org.json.JSONObject j) {
            org.json.JSONObject f = j == null ? null : j.optJSONObject("friend");
            String n = f == null ? "" : f.optString("name", "");
            if (n.isEmpty() && f != null) n = f.optString("username", "");
            name = n.isEmpty() ? "that player" : n;
        }
    }

    private static void render(Activity a, Dialog d, LinearLayout lists, TextView status, NextendoApi.Friends f, Runnable reload) {
        View focused = lists.findFocus();
        String focusKey = focused == null ? null : (String) focused.getTag();
        lists.removeAllViews();
        if (!f.requests.isEmpty()) {
            PUi.add(lists, PUi.section(a, "Friend requests (" + f.requests.size() + ")"), 12);
            LinearLayout box = PUi.add(lists, PUi.listCard(a), 6);
            for (NextendoApi.Friend r : f.requests) {
                LinearLayout row = personRow(a, r, PUi.joined(r.friendCode, "wants to be your friend"), 0);
                row.addView(action(a, "Accept", PUi.Kind.PRIMARY, "accept:" + r.pid, v -> act(a, d, status, "accept", r, "You are now friends with " + r.shown(), reload)));
                row.addView(action(a, "Decline", PUi.Kind.SOFT, "decline:" + r.pid, v -> act(a, d, status, "decline", r, "Declined " + r.shown() + "'s request", reload)));
                box.addView(row);
            }
        }
        int online = 0;
        for (NextendoApi.Friend x : f.friends) if (x.status > 0) online++;
        PUi.add(lists, PUi.section(a, f.friends.isEmpty() ? "Friends" : "Friends: " + online + " of " + f.friends.size() + " online"), 12);
        if (f.friends.isEmpty()) {
            PUi.add(lists, PUi.small(a, "No friends yet. Send your friend code to someone, or type theirs above."), 6);
        } else {
            LinearLayout box = PUi.add(lists, PUi.listCard(a), 6);
            for (NextendoApi.Friend x : f.friends) {
                LinearLayout row = personRow(a, x, presence(x), x.playing() ? 0 : -1);
                if (x.playing()) {
                    ImageView icon = new ImageView(a);
                    icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    icon.setClipToOutline(true);
                    int r = PUi.dp(a, 8);
                    icon.setOutlineProvider(new ViewOutlineProvider() { @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); } });
                    icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(PUi.dp(a, 40), PUi.dp(a, 40));
                    il.setMarginEnd(PUi.dp(a, 8));
                    row.addView(icon, il);
                    NextendoApi.picture(0, x.titleId, b -> { if (b != null) icon.setImageBitmap(b); else icon.setVisibility(View.GONE); });
                }
                row.addView(action(a, "Remove", PUi.Kind.QUIET, "remove:" + x.pid, v ->
                        PUi.confirmDanger(a, "Remove " + x.shown() + "?", x.shown() + " is taken off your Nextendo friend list, everywhere you use Nextendo. You can send a request again later.",
                                "Remove", () -> act(a, d, status, "remove", x, "Removed " + x.shown(), reload))));
                box.addView(row);
            }
        }
        if (!f.sent.isEmpty()) {
            PUi.add(lists, PUi.section(a, "Requests you sent (" + f.sent.size() + ")"), 12);
            LinearLayout box = PUi.add(lists, PUi.listCard(a), 6);
            for (NextendoApi.Friend s : f.sent) box.addView(personRow(a, s, PUi.joined(s.friendCode, "waiting for an answer"), -1));
        }
        if (focusKey != null) {
            View again = lists.findViewWithTag(focusKey);
            if (again != null) again.requestFocus();
        }
    }

    /** What a friend is doing: "Playing <game> · <detail> · on <platform>", "Online", or "Offline" */
    private static CharSequence presence(NextendoApi.Friend x) {
        if (x.playing()) {
            String game = !x.game.isEmpty() ? x.game : NextendoApi.countName(x.titleId);
            return PUi.joined("Playing " + (game == null || game.isEmpty() ? "a game" : game), x.detail.isEmpty() ? null : x.detail, x.platform.isEmpty() ? null : "on " + x.platform);
        }
        if (x.status > 0) return PUi.joined("Online", x.platform.isEmpty() ? null : "on " + x.platform);
        return "Offline";
    }

    /** A person's row: picture with a presence dot (online: the leaf colour), name, the line under it; buttons are added after it */
    private static LinearLayout personRow(Activity a, NextendoApi.Friend x, CharSequence line, int unused) {
        LinearLayout row = PUi.row(a);
        int p = PUi.dp(a, 8);
        row.setPadding(p, p, p, p);
        FrameLayout pic = new FrameLayout(a);
        pic.addView(face(a, x.shown(), x.pid, 44, 12));
        if (x.status > 0) {
            View dot = new View(a);
            dot.setBackground(PUi.round(PUi.col(a, R.color.bn_leaf), PUi.dp(a, 999), PUi.col(a, R.color.bn_card), PUi.dp(a, 2)));
            pic.addView(dot, new FrameLayout.LayoutParams(PUi.dp(a, 14), PUi.dp(a, 14), Gravity.BOTTOM | Gravity.END));
        }
        row.addView(pic, new LinearLayout.LayoutParams(PUi.dp(a, 48), PUi.dp(a, 48)));
        LinearLayout text = new LinearLayout(a);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView n = PUi.text(a, (x.favorite ? "★ " : "") + x.shown(), 16, R.color.bn_text, 700);
        n.setSingleLine(true); n.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(n, PUi.fill());
        TextView l = PUi.text(a, line, 13, x.playing() ? R.color.bn_leaf : R.color.bn_text_2, x.playing() ? 600 : 400);
        l.setMaxLines(2); l.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(l, PUi.fill());
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.setMarginStart(PUi.dp(a, 12)); tl.setMarginEnd(PUi.dp(a, 8));
        row.addView(text, tl);
        row.setContentDescription(x.shown() + ", " + line);
        return row;
    }

    private static View action(Activity a, String label, PUi.Kind kind, String tag, View.OnClickListener l) {
        View b = PUi.button(a, label, kind, Feel.button(l));
        b.setTag(tag);
        LinearLayout.LayoutParams lp = PUi.wrap(); lp.setMarginStart(PUi.dp(a, 6));
        b.setLayoutParams(lp);
        return b;
    }

    private static void act(Activity a, Dialog d, TextView status, String action, NextendoApi.Friend x, String done, Runnable reload) {
        status.setText("Asking Nextendo…");
        NextendoApi.friendAction(a, action, x.pid, r -> {
            if (!d.isShowing()) return;
            status.setText(r.ok() ? done : r.error);
            reload.run();
        });
    }
}

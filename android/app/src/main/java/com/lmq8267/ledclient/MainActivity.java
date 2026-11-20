package com.lmq8267.ledclient;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Filter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.AdapterView;

import java.util.ArrayList;

public class MainActivity extends Activity {

    // ── 配色方案 ──────────────────────────────────────────
    private static final int COLOR_HEADER   = Color.rgb(15, 23, 42);    // slate-900
    private static final int COLOR_BG       = Color.rgb(241, 245, 249); // slate-100
    private static final int COLOR_CARD     = Color.WHITE;
    private static final int COLOR_TEXT     = Color.rgb(15, 23, 42);    // slate-900
    private static final int COLOR_MUTED    = Color.rgb(100, 116, 139); // slate-500
    private static final int COLOR_BORDER   = Color.rgb(226, 232, 240); // slate-200
    private static final int COLOR_INPUT_BG = Color.rgb(248, 250, 252); // slate-50
    private static final int COLOR_ACCENT   = Color.rgb(59, 130, 246);  // blue-500
    private static final int COLOR_GREEN    = Color.rgb(34, 197, 94);   // green-500
    private static final int COLOR_RED      = Color.rgb(239, 68, 68);   // red-500
    private static final int COLOR_LOGS_BG  = Color.rgb(248, 250, 252); // slate-50

    // ── 视图引用 ──────────────────────────────────────────
    private AutoCompleteTextView serverInput;
    private Switch bootSwitch;
    private Button runButton;
    private LinearLayout contentArea;
    private ScrollView scrollView;
    private TextView headerTitle;
    private TextView headerSubtitle;
    private View statusDot;
    private TextView statusText;
    private TextView navHome;
    private TextView navLogs;
    private TextView navAbout;
    private LinearLayout logList;
    private TextView serverStatusTitle;
    private TextView serverStatusBadge;
    private boolean suppressBootChange;
    private int page = 0;
    private String lastLogText = null;
    private long lastHomeNavTapAt = 0;
    private long lastLogsNavTapAt = 0;
    private long lastAboutNavTapAt = 0;
    private long logsAutoScrollPausedUntil = 0;
    private long lastRecoveryAttemptAt = 0L;

    // ── 定时器 ────────────────────────────────────────────
    private final Handler tickerHandler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            boolean enabled = Prefs.enabled(MainActivity.this);
            updateRunButton(enabled);
            updateStatus(isAlive());
            updateServerStatusCard();
            recoverServiceIfNeeded();
            if (page == 1) refreshLogs(shouldAutoScrollLogs());
            tickerHandler.postDelayed(this, 1000);
        }
    };

    // ═══════════════════════════════════════════════════════
    //  生命周期
    // ═══════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        buildShell();
        load();
        renderHome();
        requestIdleOverlayCleanup();
        requestStartupPermissions(false);
        autoStartIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateRunButton(Prefs.enabled(this));
        updateStatus(isAlive());
        tickerHandler.removeCallbacks(ticker);
        tickerHandler.postDelayed(ticker, 1000);
        renderCurrentPage();
        updateServerStatusCard();
        // 用户从后台回到应用时也补一次恢复：onCreate 只会在首次创建时执行，
        // 而系统可能在页面停留期间把服务杀掉。
        autoStartIfNeeded();
    }

    @Override
    protected void onPause() {
        // 页面切换/进后台时把输入框里未失焦的地址落盘；地址有变化就按“保存并重启”处理，
        // 否则界面显示新地址、服务却还连着旧地址，pending ACK 也会被清到新服务端。
        saveServerSettings(true);
        tickerHandler.removeCallbacks(ticker);
        super.onPause();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN && serverInput != null && serverInput.hasFocus()) {
            Rect bounds = new Rect();
            serverInput.getGlobalVisibleRect(bounds);
            int x = (int) event.getRawX();
            int y = (int) event.getRawY();
            if (!bounds.contains(x, y)) {
                serverInput.clearFocus();
                serverInput.dismissDropDown();
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(serverInput.getWindowToken(), 0);
            }
        }
        return super.dispatchTouchEvent(event);
    }

    // ═══════════════════════════════════════════════════════
    //  页面容器构建
    // ═══════════════════════════════════════════════════════

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(14), dp(16), dp(14));
        header.setBackgroundColor(COLOR_HEADER);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_stat_led);
        icon.setColorFilter(Color.WHITE);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        iconLp.rightMargin = dp(12);
        iconLp.gravity = Gravity.CENTER_VERTICAL;
        GradientDrawable iconBg = new GradientDrawable();
        iconBg.setColor(Color.rgb(225, 29, 72));
        iconBg.setCornerRadius(dp(10));
        setBackgroundCompat(icon, iconBg);
        icon.setPadding(dp(7), dp(7), dp(7), dp(7));

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setGravity(Gravity.CENTER_VERTICAL);

        headerTitle = new TextView(this);
        headerTitle.setText(R.string.app_name);
        headerTitle.setTextColor(Color.WHITE);
        headerTitle.setTextSize(19);
        headerTitle.setTypeface(Typeface.DEFAULT_BOLD);

        headerSubtitle = new TextView(this);
        headerSubtitle.setText(getString(R.string.menu_home));
        headerSubtitle.setTextColor(Color.rgb(148, 163, 184));
        headerSubtitle.setTextSize(12);
        headerSubtitle.setPadding(0, dp(2), 0, 0);

        titleBox.addView(headerTitle, fullWidth(-2, -2));
        titleBox.addView(headerSubtitle, fullWidth(-2, -2));

        LinearLayout statusPill = new LinearLayout(this);
        statusPill.setOrientation(LinearLayout.HORIZONTAL);
        statusPill.setGravity(Gravity.CENTER_VERTICAL);
        statusPill.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(Color.argb(35, 255, 255, 255));
        pillBg.setCornerRadius(dp(999));
        setBackgroundCompat(statusPill, pillBg);

        statusDot = new View(this);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.rightMargin = dp(6);
        statusPill.addView(statusDot, dotLp);

        statusText = new TextView(this);
        statusText.setTextSize(12);
        statusText.setTypeface(Typeface.DEFAULT_BOLD);
        statusPill.addView(statusText, new LinearLayout.LayoutParams(-2, -2));

        header.addView(icon, iconLp);
        header.addView(titleBox, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(statusPill, new LinearLayout.LayoutParams(-2, -2));

        View headerShadow = new View(this);
        headerShadow.setBackgroundColor(Color.argb(30, 0, 0, 0));

        scrollView = new ScrollView(this);
        scrollView.setFocusable(true);
        scrollView.setFocusableInTouchMode(false);
        scrollView.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        scrollView.setPadding(0, 0, 0, dp(12));
        scrollView.setClipToPadding(false);
        scrollView.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int amount = Math.max(dp(96), scrollView.getHeight() / 3);
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (page == 1) pauseLogsAutoScroll();
                    scrollView.smoothScrollBy(0, amount);
                    return false;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (page == 1) pauseLogsAutoScroll();
                    scrollView.smoothScrollBy(0, -amount);
                    return false;
                }
                if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN) {
                    if (page == 1) pauseLogsAutoScroll();
                    scrollView.smoothScrollBy(0, scrollView.getHeight());
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_PAGE_UP) {
                    if (page == 1) pauseLogsAutoScroll();
                    scrollView.smoothScrollBy(0, -scrollView.getHeight());
                    return true;
                }
                return false;
            }
        });
        scrollView.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent event) {
                if (page == 1) {
                    int action = event.getActionMasked();
                    if (action == MotionEvent.ACTION_DOWN
                            || action == MotionEvent.ACTION_MOVE
                            || action == MotionEvent.ACTION_UP
                            || action == MotionEvent.ACTION_CANCEL) {
                        pauseLogsAutoScroll();
                    }
                }
                return false;
            }
        });

        contentArea = new LinearLayout(this);
        contentArea.setOrientation(LinearLayout.VERTICAL);
        contentArea.setPadding(dp(14), dp(14), dp(14), dp(14));
        scrollView.addView(contentArea, new ScrollView.LayoutParams(-1, -2));

        LinearLayout bottomNav = new LinearLayout(this);
        bottomNav.setOrientation(LinearLayout.HORIZONTAL);
        bottomNav.setGravity(Gravity.CENTER);
        bottomNav.setPadding(dp(10), dp(8), dp(10), dp(10));
        bottomNav.setBackgroundColor(COLOR_CARD);
        navHome = navItem(getString(R.string.menu_home), 0);
        navLogs = navItem(getString(R.string.logs), 1);
        navAbout = navItem(getString(R.string.menu_about), 2);
        LinearLayout.LayoutParams navLp = new LinearLayout.LayoutParams(0, dp(44), 1);
        navLp.leftMargin = dp(4);
        navLp.rightMargin = dp(4);
        bottomNav.addView(navHome, navLp);
        bottomNav.addView(navLogs, navLp);
        bottomNav.addView(navAbout, navLp);

        root.addView(header, new LinearLayout.LayoutParams(-1, -2));
        root.addView(headerShadow, new LinearLayout.LayoutParams(-1, dp(2)));
        root.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(bottomNav, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        updateNavigation();
        updateStatus(isAlive());
    }

    private TextView navItem(String text, final int targetPage) {
        TextView item = new TextView(this);
        item.setText(text);
        item.setGravity(Gravity.CENTER);
        item.setTextSize(14);
        item.setTypeface(Typeface.DEFAULT_BOLD);
        makeFocusableAction(item);
        item.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (page == targetPage) {
                    handleNavRetap(targetPage);
                    return;
                }
                page = targetPage;
                renderCurrentPage();
                if (targetPage == 0 && scrollView != null) {
                    scrollView.post(new Runnable() {
                        @Override public void run() {
                            scrollView.scrollTo(0, 0);
                        }
                    });
                }
            }
        });
        return item;
    }

    private void handleNavRetap(int targetPage) {
        long now = System.currentTimeMillis();
        long lastTap;
        if (targetPage == 0) lastTap = lastHomeNavTapAt;
        else if (targetPage == 1) lastTap = lastLogsNavTapAt;
        else lastTap = lastAboutNavTapAt;

        if (now - lastTap < 350 && scrollView != null) {
            if (targetPage == 1) pauseLogsAutoScroll();
            scrollView.smoothScrollTo(0, 0);
        }

        if (targetPage == 0) lastHomeNavTapAt = now;
        else if (targetPage == 1) lastLogsNavTapAt = now;
        else lastAboutNavTapAt = now;
    }

    private void updateNavigation() {
        updateNavItem(navHome, page == 0);
        updateNavItem(navLogs, page == 1);
        updateNavItem(navAbout, page == 2);
    }

    private void updateNavItem(TextView item, boolean selected) {
        if (item == null) return;
        item.setTextColor(selected ? Color.WHITE : COLOR_MUTED);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? COLOR_HEADER : Color.TRANSPARENT);
        bg.setCornerRadius(dp(12));
        if (item.hasFocus()) {
            bg.setColor(selected ? COLOR_HEADER : Color.rgb(239, 246, 255));
            bg.setStroke(dp(2), COLOR_ACCENT);
        }
        setBackgroundCompat(item, bg);
    }

    // ═══════════════════════════════════════════════════════
    //  页面路由
    // ═══════════════════════════════════════════════════════

    private void renderCurrentPage() {
        logList = null;
        lastLogText = null;
        serverStatusTitle = null;
        serverStatusBadge = null;
        if (page == 1) renderLogsPage();
        else if (page == 2) renderAboutPage();
        else renderHome();
        updateNavigation();
    }

    // ═══════════════════════════════════════════════════════
    //  首页
    // ═══════════════════════════════════════════════════════

    private void renderHome() {
        if (contentArea == null) return;
        contentArea.removeAllViews();
        headerSubtitle.setText(R.string.menu_home);
        addStatusCard();
        addConfigCard();
        addHistoryCard();
    }

    private void addStatusCard() {
        LinearLayout card = cardContainer();
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        serverStatusTitle = title;
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);

        TextView desc = new TextView(this);
        String server = displayAddress(Prefs.server(this));
        desc.setText(server.length() == 0 ? getString(R.string.server_address_hint_short) : server);
        desc.setTextColor(COLOR_MUTED);
        desc.setTextSize(13);
        desc.setPadding(0, dp(4), 0, 0);

        textBox.addView(title, fullWidth(-2, -2));
        textBox.addView(desc, fullWidth(-2, -2));

        TextView badge = new TextView(this);
        serverStatusBadge = badge;
        badge.setTextSize(12);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(12), dp(7), dp(12), dp(7));
        badge.setTextColor(Color.WHITE);
        updateServerStatusCard();

        row.addView(textBox, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(badge, new LinearLayout.LayoutParams(-2, -2));
        card.addView(row, fullWidth(-2, -2));

        addCard(card);
    }

    // ── 配置卡片 ──
    private void addConfigCard() {
        LinearLayout card = cardContainer();

        card.addView(sectionTitle(getString(R.string.connection_settings), getString(R.string.connection_settings_desc)));
        card.addView(divider());

        TextView labelServer = label(getString(R.string.server_address));
        card.addView(labelServer, fullWidth(-2, 20));

        serverInput = new AutoCompleteTextView(this);
        serverInput.setHint(getString(R.string.server_address_hint));
        serverInput.setText(displayAddress(Prefs.server(this)));
        serverInput.setThreshold(0);
        refreshServerSuggestions();
        serverInput.setSingleLine(true);
        // 必须显式设置 inputType：不设置时部分电视固件把它当成 TYPE_NULL，
        // 输入法认为这不是可编辑控件，点进去像只读框、键盘永远不弹（MiTV-MFTR0 实测）。
        // URI 变体让电视键盘直接给出 : 和 . ，填 IP:端口 更省事。
        serverInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        serverInput.setTextSize(13);
        serverInput.setDropDownVerticalOffset(dp(6));
        GradientDrawable popupBg = new GradientDrawable();
        popupBg.setColor(COLOR_CARD);
        popupBg.setStroke(dp(1), COLOR_BORDER);
        popupBg.setCornerRadius(dp(12));
        serverInput.setDropDownBackgroundDrawable(popupBg);
        serverInput.setMinHeight(0);
        serverInput.setMinimumHeight(0);
        serverInput.setIncludeFontPadding(false);
        serverInput.setGravity(Gravity.CENTER_VERTICAL);
        serverInput.setTextColor(COLOR_TEXT);
        serverInput.setHintTextColor(Color.rgb(148, 163, 184));
        serverInput.setPadding(dp(10), 0, dp(10), 0);
        GradientDrawable inputBorder = new GradientDrawable();
        inputBorder.setColor(COLOR_INPUT_BG);
        inputBorder.setStroke(dp(1), Color.rgb(203, 213, 225));
        inputBorder.setCornerRadius(dp(12));
        setBackgroundCompat(serverInput, inputBorder);
        serverInput.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                applyInputFocusStyle(serverInput, hasFocus);
                if (hasFocus) {
                    // 这里不再 showDropDown：电视遥控器把焦点移到输入框时弹出的下拉会抢走焦点，
                    // 结果“确定键”被下拉列表吃掉、输入法起不来。下拉改由触摸点击触发。
                    refreshServerSuggestions();
                } else {
                    saveAndRestartIfServerChanged();
                }
            }
        });
        // 电视遥控器的确定键不会自动拉起输入法，AutoCompleteTextView 的历史下拉还会吃掉按键。
        // 这里显式把确定键转成“收起下拉 + 弹输入法”，否则点进输入框后无法输入。
        serverInput.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (!isConfirmKey(keyCode)) return false;
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    serverInput.dismissDropDown();
                    showIme(serverInput);
                }
                return true;   // DOWN/UP 都消费，避免再触发一次 click 又把下拉弹出来
            }
        });
        serverInput.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                refreshServerSuggestions();
                // 有历史地址时先让用户选；没有历史就直接弹键盘，
                // 否则触摸点击看起来“没有任何反应”。
                if (serverInput.getAdapter() != null && serverInput.getAdapter().getCount() > 0) {
                    serverInput.showDropDown();
                } else {
                    showIme(serverInput);
                }
            }
        });
        serverInput.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                saveAndRestartIfServerChanged();
                serverInput.clearFocus();
            }
        });
        card.addView(serverInput, fullWidth(-2, 36));

        LinearLayout bootRow = new LinearLayout(this);
        bootRow.setOrientation(LinearLayout.HORIZONTAL);
        bootRow.setGravity(Gravity.CENTER_VERTICAL);
        bootRow.setPadding(0, dp(12), 0, dp(8));

        TextView bootLabel = new TextView(this);
        bootLabel.setText(getString(R.string.boot_start));
        bootLabel.setTextColor(COLOR_TEXT);
        bootLabel.setTextSize(16);
        bootLabel.setTypeface(Typeface.DEFAULT_BOLD);

        bootSwitch = new Switch(this);
        suppressBootChange = true;
        bootSwitch.setChecked(Prefs.boot(this));
        suppressBootChange = false;
        bootSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button, boolean checked) {
                if (!suppressBootChange) setBootStart(checked);
            }
        });
        bootSwitch.setFocusable(false);
        bootSwitch.setClickable(false);
        bootRow.addView(bootLabel, new LinearLayout.LayoutParams(0, -2, 1));
        bootRow.addView(bootSwitch, new LinearLayout.LayoutParams(-2, -2));
        makeFocusableAction(bootRow);
        bootRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                bootSwitch.setChecked(!bootSwitch.isChecked());
            }
        });
        card.addView(bootRow, fullWidth(-2, -2));

        card.addView(spacer(dp(4)));

        runButton = new Button(this);
        runButton.setText(getString(R.string.start));
        runButton.setTextColor(Color.WHITE);
        runButton.setTextSize(16);
        runButton.setTypeface(Typeface.DEFAULT_BOLD);
        runButton.setPadding(0, dp(14), 0, dp(14));
        runButton.setOnTouchListener(buttonTouch);
        makeFocusableAction(runButton);
        runButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                save();
                if (Prefs.enabled(MainActivity.this)) stopClient();
                else startClient(true);
            }
        });
        updateRunButton(Prefs.enabled(this));
        card.addView(runButton, fullWidth(-2, -2));

        addCard(card);
    }

    // ── 历史消息卡片 ──
    private void addHistoryCard() {
        LinearLayout card = cardContainer();

        View historyTitle = sectionTitle(getString(R.string.history_messages), getString(R.string.history_messages_desc));
        makeFocusableAction(historyTitle);
        historyTitle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                confirmClearHistory();
            }
        });
        card.addView(historyTitle);
        card.addView(divider());

        ArrayList<MessageHistory.Entry> entries = MessageHistory.read(this, 100);
        if (entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(getString(R.string.history_empty));
            empty.setTextColor(COLOR_MUTED);
            empty.setTextSize(15);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(36), 0, dp(36));
            card.addView(empty, fullWidth(-2, -2));
        } else {
            int row = 0;
            for (int i = entries.size() - 1; i >= 0; i--) {
                MessageHistory.Entry entry = entries.get(i);
                LinearLayout item = new LinearLayout(this);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setPadding(dp(14), dp(12), dp(14), dp(12));

                GradientDrawable itemBg = new GradientDrawable();
                itemBg.setColor((row % 2 == 0) ? Color.rgb(248, 250, 252) : Color.rgb(239, 246, 255));
                itemBg.setCornerRadius(dp(10));
                itemBg.setStroke(dp(1), row % 2 == 0 ? Color.rgb(226, 232, 240) : Color.rgb(191, 219, 254));
                setBackgroundCompat(item, itemBg);
                makeFocusablePanel(item);

                LinearLayout metaRow = new LinearLayout(this);
                metaRow.setOrientation(LinearLayout.HORIZONTAL);
                metaRow.setGravity(Gravity.CENTER_VERTICAL);

                TextView timeView = new TextView(this);
                timeView.setText(entry.time);
                timeView.setTextColor(COLOR_MUTED);
                timeView.setTextSize(12);
                timeView.setSingleLine(true);
                metaRow.addView(timeView, new LinearLayout.LayoutParams(0, -2, 1));

                if (entry.taskId.length() > 0) {
                    TextView idTag = new TextView(this);
                    idTag.setText(entry.taskId);
                    idTag.setTextColor(COLOR_ACCENT);
                    idTag.setTextSize(11);
                    idTag.setTypeface(Typeface.DEFAULT_BOLD);
                    idTag.setGravity(Gravity.CENTER);
                    idTag.setPadding(dp(8), dp(3), dp(8), dp(3));
                    // 任务号现在带运行标记（t<时间戳>-<随机段>-<序号>），比原来的 t8 长很多；
                    // 单行 + 中间省略，避免在手机窄屏上把时间挤没或撑出卡片。
                    idTag.setSingleLine(true);
                    idTag.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                    idTag.setMaxWidth(dp(190));
                    GradientDrawable tagBg = new GradientDrawable();
                    tagBg.setColor(Color.rgb(239, 246, 255));
                    tagBg.setStroke(dp(1), Color.rgb(191, 219, 254));
                    tagBg.setCornerRadius(dp(999));
                    setBackgroundCompat(idTag, tagBg);
                    LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(-2, -2);
                    tagLp.leftMargin = dp(8);
                    metaRow.addView(idTag, tagLp);
                }

                item.addView(metaRow, fullWidth(-2, -2));

                TextView msgView = new TextView(this);
                msgView.setText(entry.text);
                msgView.setTextColor(COLOR_TEXT);
                msgView.setTextSize(15);
                msgView.setPadding(0, dp(4), 0, 0);
                msgView.setLineSpacing(dp(2), 1);
                msgView.setSingleLine(false);
                msgView.setEllipsize(null);
                msgView.setHorizontallyScrolling(false);
                if (Build.VERSION.SDK_INT >= 23) msgView.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY);
                item.addView(msgView, fullWidth(-2, -2));

                LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(-1, -2);
                if (row < entries.size() - 1) itemLp.bottomMargin = dp(8);
                card.addView(item, itemLp);
                row++;
            }
        }

        addCard(card);
    }

    private void confirmClearHistory() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(18), dp(18), dp(16));
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(COLOR_CARD);
        panelBg.setCornerRadius(dp(14));
        panelBg.setStroke(dp(1), COLOR_BORDER);
        setBackgroundCompat(panel, panelBg);

        TextView title = new TextView(this);
        title.setText(R.string.history_messages);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        panel.addView(title, fullWidth(-2, -2));

        TextView message = new TextView(this);
        message.setText(R.string.confirm_clear_history);
        message.setTextColor(COLOR_MUTED);
        message.setTextSize(14);
        message.setPadding(0, dp(8), 0, dp(16));
        panel.addView(message, fullWidth(-2, -2));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button cancel = compactButton(getString(R.string.cancel), Color.rgb(100, 116, 139));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
            }
        });
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(42), 1));

        Button clear = compactButton(getString(R.string.clear), COLOR_RED);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                MessageHistory.clear(MainActivity.this);
                dialog.dismiss();
                renderHome();
                showTip(getString(R.string.toast_history_cleared), true);
            }
        });
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(0, dp(42), 1);
        clearLp.leftMargin = dp(8);
        actions.addView(clear, clearLp);

        panel.addView(actions, fullWidth(-2, -2));
        dialog.setContentView(panel);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(360));
            window.setLayout(width, -2);
        }
        cancel.requestFocus();
    }

    // ═══════════════════════════════════════════════════════
    //  日志页
    // ═══════════════════════════════════════════════════════

    private void renderLogsPage() {
        contentArea.removeAllViews();
        headerSubtitle.setText(R.string.logs);
        logsAutoScrollPausedUntil = 0;

        LinearLayout card = cardContainer();
        card.addView(sectionTitle(getString(R.string.logs), getString(R.string.logs_desc)), fullWidth(-2, -2));

        Button copyBtn = compactButton(getString(R.string.copy_all), COLOR_ACCENT);
        copyBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager mgr = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                mgr.setPrimaryClip(ClipData.newPlainText("LED logs", LogStore.read(MainActivity.this)));
                showTip(getString(R.string.toast_logs_copied), true);
            }
        });

        Button clearBtn = compactButton(getString(R.string.clear_logs), Color.rgb(107, 114, 128));
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                LogStore.clear(MainActivity.this);
                renderLogsPage();
                showTip(getString(R.string.toast_logs_cleared), true);
            }
        });

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(copyBtn, new LinearLayout.LayoutParams(0, dp(40), 1));
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(0, dp(40), 1);
        clearLp.leftMargin = dp(8);
        actions.addView(clearBtn, clearLp);
        card.addView(actions, fullWidth(-2, -2));
        card.addView(divider());

        logList = new LinearLayout(this);
        logList.setOrientation(LinearLayout.VERTICAL);
        logList.setPadding(dp(8), dp(8), dp(8), dp(72));

        GradientDrawable logBg = new GradientDrawable();
        logBg.setColor(COLOR_LOGS_BG);
        logBg.setCornerRadius(dp(10));
        logBg.setStroke(dp(1), Color.rgb(226, 232, 240));
        setBackgroundCompat(logList, logBg);

        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(-1, -2);
        logList.setMinimumHeight(dp(160));
        card.addView(logList, logLp);

        addCard(card);
        refreshLogs(true);
    }

    private void refreshLogs(final boolean scrollToBottom) {
        if (logList == null) return;
        String logs = LogStore.read(this);
        String display = logs.length() == 0 ? getString(R.string.logs_empty) : logs;
        if (!display.equals(lastLogText)) {
            lastLogText = display;
            renderLogLines(logs);
        }
        if (scrollToBottom && scrollView != null) {
            scrollView.post(new Runnable() {
                @Override public void run() {
                    scrollView.smoothScrollTo(0, Math.max(0, contentArea.getHeight() - scrollView.getHeight()));
                }
            });
        }
    }

    private boolean shouldAutoScrollLogs() {
        return System.currentTimeMillis() >= logsAutoScrollPausedUntil;
    }

    private void pauseLogsAutoScroll() {
        logsAutoScrollPausedUntil = System.currentTimeMillis() + 10000;
    }

    private void renderLogLines(String logs) {
        if (logList == null) return;
        logList.removeAllViews();
        if (logs.length() == 0) {
            TextView empty = logLineView(getString(R.string.logs_empty), 0);
            empty.setGravity(Gravity.CENTER);
            logList.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        String[] lines = logs.split("\\n");
        int start = Math.max(0, lines.length - 200);
        int row = 0;
        for (int i = start; i < lines.length; i++) {
            if (lines[i].length() == 0) continue;
            TextView item = logLineView(lines[i], row);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            if (i < lines.length - 1) lp.bottomMargin = dp(6);
            logList.addView(item, lp);
            row++;
        }
    }

    private TextView logLineView(String text, int row) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.rgb(55, 65, 81));
        tv.setTextSize(12);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setPadding(dp(10), dp(8), dp(10), dp(8));
        tv.setSingleLine(false);
        tv.setHorizontallyScrolling(false);
        if (Build.VERSION.SDK_INT >= 23) tv.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY);
        tv.setLineSpacing(dp(2), 1);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor((row % 2 == 0) ? Color.rgb(255, 255, 255) : Color.rgb(239, 246, 255));
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), (row % 2 == 0) ? Color.rgb(226, 232, 240) : Color.rgb(191, 219, 254));
        setBackgroundCompat(tv, bg);
        makeFocusablePanel(tv);
        return tv;
    }

    // ═══════════════════════════════════════════════════════
    //  关于页（修复了版本号不显示的问题）
    // ═══════════════════════════════════════════════════════

    private void renderAboutPage() {
        contentArea.removeAllViews();
        headerSubtitle.setText(R.string.menu_about);

        LinearLayout card = cardContainer();

        LinearLayout logoArea = new LinearLayout(this);
        logoArea.setOrientation(LinearLayout.VERTICAL);
        logoArea.setGravity(Gravity.CENTER);
        logoArea.setPadding(0, dp(20), 0, dp(16));

        ImageView appIcon = new ImageView(this);
        appIcon.setImageResource(R.drawable.ic_launcher);
        appIcon.setLayoutParams(new LinearLayout.LayoutParams(dp(64), dp(64)));

        TextView appName = new TextView(this);
        appName.setText(R.string.app_name);
        appName.setTextColor(COLOR_TEXT);
        appName.setTextSize(22);
        appName.setTypeface(Typeface.DEFAULT_BOLD);
        appName.setGravity(Gravity.CENTER);
        appName.setPadding(0, dp(8), 0, 0);

        logoArea.addView(appIcon);
        logoArea.addView(appName, fullWidth(-2, -2));
        card.addView(logoArea, fullWidth(-2, -2));

        View sep = new View(this);
        sep.setBackgroundColor(COLOR_BORDER);
        sep.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));
        ((LinearLayout.LayoutParams) sep.getLayoutParams()).topMargin = dp(16);
        ((LinearLayout.LayoutParams) sep.getLayoutParams()).bottomMargin = dp(4);
        card.addView(sep);

        card.addView(infoRowWithDivider(getString(R.string.about_package), getPackageName()));
        card.addView(infoRowWithDivider(getString(R.string.about_version), BuildConfig.VERSION_NAME, true));
        card.addView(infoRow(getString(R.string.about_android),
                Build.VERSION.RELEASE + " / SDK " + Build.VERSION.SDK_INT));

        addCard(card);
        addPermissionStatusCard();
        addTvHelpCard();
    }

    private void addPermissionStatusCard() {
        LinearLayout card = cardContainer();
        card.addView(sectionTitle(getString(R.string.permission_status), getString(R.string.permission_status_desc)));
        card.addView(divider());

        boolean notificationOk = Build.VERSION.SDK_INT < 33
                || checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
        card.addView(permissionRowWithDivider(
                getString(R.string.permission_notifications),
                notificationOk ? getString(R.string.permission_granted) : getString(R.string.permission_missing),
                notificationOk || Build.VERSION.SDK_INT < 33,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        openNotificationSettings();
                    }
                }));

        boolean overlayOk = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        card.addView(permissionRowWithDivider(
                getString(R.string.permission_overlay),
                overlayOk ? getString(R.string.permission_granted) : getString(R.string.permission_missing),
                overlayOk,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        openOverlaySettings();
                    }
                }));

        boolean notifAccessOk = isNotificationListenerEnabled();
        card.addView(permissionRowWithDivider(
                getString(R.string.permission_notification_access),
                notifAccessOk ? getString(R.string.permission_granted) : getString(R.string.permission_missing),
                notifAccessOk,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        openNotificationListenerSettings();
                    }
                }));

        // 电视固件经常裁掉“特殊权限”入口，但 AOSP TV 设置的 AppsActivity 还在：
        // 从这里能进 App permissions / Special app access，手动设置每个应用的悬浮窗和通知使用权。
        card.addView(permissionRowWithDivider(
                getString(R.string.permission_app_management),
                getString(R.string.permission_open),
                true,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        openAppManagementRow();
                    }
                }));

        boolean batteryOk = true;
        if (Build.VERSION.SDK_INT >= 23 && !isTv()) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            batteryOk = pm == null || pm.isIgnoringBatteryOptimizations(getPackageName());
        }
        card.addView(permissionRowWithDivider(
                getString(R.string.permission_battery),
                Build.VERSION.SDK_INT < 23
                        ? getString(R.string.permission_not_required)
                        : (batteryOk ? getString(R.string.permission_ignored) : getString(R.string.permission_not_ignored)),
                batteryOk,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        openBatteryOptimizationSettings();
                    }
                }));

        card.addView(permissionRowWithDivider(
                getString(R.string.permission_boot),
                Prefs.boot(this) ? getString(R.string.permission_enabled) : getString(R.string.permission_disabled),
                Prefs.boot(this),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        // 系统级的“自启动白名单”是厂商私有的，只能把用户带到应用详情，
                        // 由他自己在“应用管理/自启动”里放行；这里同时给一句路径提示。
                        showTip(getString(R.string.toast_boot_system_hint), false);
                        openAppDetailsSettings();
                    }
                }));

        addCard(card);
    }

    /** 电视端 ADB 授权帮助卡片 */
    private void addTvHelpCard() {
        LinearLayout card = cardContainer();
        card.addView(sectionTitle(getString(R.string.tv_help_title), getString(R.string.tv_help_desc)));
        card.addView(divider());

        final String pkg = getPackageName();
        final String notifComponent = pkg + "/" + pkg + ".LedNotifProbe";
        // 前三条是装机必需：悬浮窗、通知使用权（强停后复活）、后台运行兜底；第四条只用于验证。
        final String[] cmds = new String[] {
                getString(R.string.tv_adb_command, pkg),
                getString(R.string.tv_adb_command2, notifComponent),
                getString(R.string.tv_adb_command3, pkg),
                getString(R.string.tv_adb_command4),
        };
        final String allCmds = cmds[0] + "\n" + cmds[1] + "\n" + cmds[2] + "\n" + cmds[3];

        for (int i = 0; i < cmds.length; i++) {
            TextView codeView = new TextView(this);
            codeView.setText(cmds[i]);
            codeView.setTextColor(COLOR_TEXT);
            codeView.setTextSize(12);
            codeView.setTypeface(Typeface.MONOSPACE);
            codeView.setPadding(dp(12), dp(10), dp(12), dp(10));
            codeView.setSingleLine(false);
            codeView.setHorizontallyScrolling(false);
            codeView.setLineSpacing(dp(2), 1);
            if (Build.VERSION.SDK_INT >= 23)
                codeView.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY);
            GradientDrawable codeBg = new GradientDrawable();
            codeBg.setColor(Color.rgb(248, 250, 252));
            codeBg.setStroke(dp(1), Color.rgb(226, 232, 240));
            codeBg.setCornerRadius(dp(10));
            setBackgroundCompat(codeView, codeBg);
            makeFocusablePanel(codeView);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            if (i < cmds.length - 1) lp.bottomMargin = dp(6);
            card.addView(codeView, lp);
        }

        card.addView(spacer(dp(10)));

        Button copyBtn = compactButton(getString(R.string.copy_adb), COLOR_ACCENT);
        copyBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager mgr = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                mgr.setPrimaryClip(ClipData.newPlainText("ADB", allCmds));
                showTip(getString(R.string.toast_adb_copied), true);
            }
        });
        card.addView(copyBtn, fullWidth(dp(40), -1));

        addCard(card);
    }

    /**
     * 水平方向的标签:值信息行。
     * 修复：原 infoLine 使用了 VERTICAL 的 panelSoft + 水平权重参数，
     * 导致所有子 View 宽度为 0 无法显示。
     */
    private View infoRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView left = new TextView(this);
        left.setText(label);
        left.setTextColor(COLOR_MUTED);
        left.setTextSize(14);

        TextView right = new TextView(this);
        right.setText(value);
        right.setTextColor(COLOR_TEXT);
        right.setTextSize(14);
        right.setGravity(Gravity.END);
        right.setTypeface(Typeface.DEFAULT_BOLD);

        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(right, new LinearLayout.LayoutParams(0, -2, 2));
        return row;
    }

    /**
     * 带底部线的信息行（重构版）
     */
    private LinearLayout infoRowWithDivider(String label, String value) {
        return infoRowWithDivider(label, value, false);
    }

    private LinearLayout infoRowWithDivider(String label, String value, boolean link) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView left = new TextView(this);
        left.setText(label);
        left.setTextColor(COLOR_MUTED);
        left.setTextSize(14);

        TextView right = new TextView(this);
        right.setText(value);
        right.setTextColor(COLOR_TEXT);
        right.setTextSize(14);
        right.setGravity(Gravity.END);
        right.setTypeface(Typeface.DEFAULT_BOLD);
        if (link) {
            right.setTextColor(COLOR_ACCENT);
            View.OnClickListener openRepo = new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/lmq8267/led-overlay")));
                    } catch (Exception ignored) {
                        showTip(getString(R.string.toast_open_settings_failed, getString(R.string.menu_about)), false);
                    }
                }
            };
            makeFocusableAction(row);
            row.setOnClickListener(openRepo);
        }

        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(right, new LinearLayout.LayoutParams(0, -2, 2));

        View divider = new View(this);
        divider.setBackgroundColor(COLOR_BORDER);

        container.addView(row, new LinearLayout.LayoutParams(-1, -2));
        container.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        return container;
    }

    private LinearLayout permissionRowWithDivider(String label, String value, boolean ok, View.OnClickListener listener) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        View row = permissionRow(label, value, ok, listener);
        container.addView(row, new LinearLayout.LayoutParams(-1, -2));
        View divider = new View(this);
        divider.setBackgroundColor(COLOR_BORDER);
        container.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        return container;
    }

    private View permissionRow(String label, String value, boolean ok, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView left = new TextView(this);
        left.setText(label);
        left.setTextColor(COLOR_MUTED);
        left.setTextSize(14);

        TextView right = new TextView(this);
        right.setText(value);
        right.setTextColor(ok ? COLOR_GREEN : COLOR_RED);
        right.setTextSize(14);
        right.setGravity(Gravity.END);
        right.setTypeface(Typeface.DEFAULT_BOLD);
        if (listener != null) {
            right.setPadding(dp(10), dp(6), 0, dp(6));
            makeFocusableAction(row);
            row.setOnClickListener(listener);
        }

        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(right, new LinearLayout.LayoutParams(0, -2, 2));
        return row;
    }

    private final class HistoryAdapter extends ArrayAdapter<String> {
        private final ArrayList<String> items;
        private final Filter allItemsFilter = new Filter() {
            @Override protected FilterResults performFiltering(CharSequence constraint) {
                FilterResults results = new FilterResults();
                results.values = items;
                results.count = items.size();
                return results;
            }

            @Override protected void publishResults(CharSequence constraint, FilterResults results) {
                notifyDataSetChanged();
            }
        };

        HistoryAdapter(ArrayList<String> items) {
            super(MainActivity.this, android.R.layout.simple_dropdown_item_1line, items);
            this.items = items;
        }

        @Override public Filter getFilter() {
            return allItemsFilter;
        }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            TextView item = convertView instanceof TextView ? (TextView) convertView : new TextView(MainActivity.this);
            item.setText(getItem(position));
            item.setTextColor(COLOR_TEXT);
            item.setTextSize(14);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setSingleLine(true);
            item.setPadding(dp(14), 0, dp(14), 0);
            item.setMinHeight(dp(42));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(position % 2 == 0 ? Color.WHITE : Color.rgb(248, 250, 252));
            bg.setCornerRadius(dp(8));
            setBackgroundCompat(item, bg);
            return item;
        }
    }

    // ═══════════════════════════════════════════════════════
    //  业务逻辑
    // ═══════════════════════════════════════════════════════

    private void load() {
        updateRunButton(Prefs.enabled(this));
    }

    private void save() {
        saveServerSettings(false);
    }

    private void saveAndRestartIfServerChanged() {
        saveServerSettings(true);
    }

    private void autoStartIfNeeded() {
        // 只有用户明确启用过才自动恢复。否则“停止”后重进应用又会被拉起来。
        if (!Prefs.enabled(this)) return;
        if (Prefs.server(this).trim().length() == 0) return;
        if (isAlive()) return;
        startClient(false);
    }

    /**
     * 页面一直停在前台时也要能自愈：onResume 只触发一次，服务被厂商清理后
     * ticker 仍是活的。这里做 10 秒节流，避免启动失败时每秒重试。
     */
    private void recoverServiceIfNeeded() {
        if (!Prefs.enabled(this) || Prefs.server(this).trim().length() == 0) return;
        if (isAlive()) return;
        long now = System.currentTimeMillis();
        if (now - lastRecoveryAttemptAt < 10000L) return;
        lastRecoveryAttemptAt = now;
        Prefs.setConnectionState(this, Prefs.CONNECTION_CONNECTING);
        LedService.start(this, Prefs.ACTION_RESTORE, "app");
    }

    private void requestIdleOverlayCleanup() {
        if (!isAlive()) return;
        LedService.start(this, Prefs.ACTION_CLEAN_IDLE_OVERLAY, "app");
    }

    /** 真实存活判断：enabled 是意图，心跳才是服务此刻是否还活着。 */
    private boolean isAlive() {
        return Prefs.enabled(this) && Prefs.isHeartbeatFresh(this);
    }

    private void saveServerSettings(boolean restartIfRunning) {
        if (serverInput == null) return;
        String previous = Prefs.server(this);
        String server = cleanAddress(serverInput.getText().toString());
        Prefs.store(this).edit()
                .putString(Prefs.KEY_SERVER, server)
                .putBoolean(Prefs.KEY_BOOT, bootSwitch != null && bootSwitch.isChecked())
                .apply();
        Prefs.rememberServer(this, server);
        boolean serverChanged = !server.equals(previous);
        if (serverChanged) Prefs.clearPendingAcks(this);
        if (serverInput != null)
            serverInput.setText(displayAddress(Prefs.server(this)));
        refreshServerSuggestions();
        if (restartIfRunning && Prefs.enabled(this) && serverChanged) {
            if (server.length() == 0) {
                // 地址被清空后没有可连接目标，直接按“停止”处理，避免留下一个假连接状态。
                LedService.stop(this);
                updateRunButton(false);
                updateStatus(false);
                updateServerStatusCard();
                return;
            }
            Prefs.setConnectionState(this, Prefs.CONNECTION_CONNECTING);
            LedService.start(this, Prefs.ACTION_RESTORE, "app");
            updateServerStatusCard();
        }
    }

    private void refreshServerSuggestions() {
        if (serverInput == null) return;
        ArrayList<String> history = Prefs.serverHistory(this);
        ArrayList<String> display = new ArrayList<String>();
        for (int i = 0; i < history.size(); i++) display.add(displayAddress(history.get(i)));
        HistoryAdapter adapter = new HistoryAdapter(display);
        serverInput.setAdapter(adapter);
    }

    private void startClient(boolean showToast) {
        if (Prefs.server(this).trim().length() == 0) {
            showTip(getString(R.string.toast_server_empty), false);
            return;
        }
        requestStartupPermissions(showToast);

        // 悬浮窗和通知使用权缺一项都还能“连上”，但要么显示不出来、要么被杀后没人复活。
        // 这里只提示、不阻止启动，避免用户因为暂时没执行 ADB 就完全无法使用。
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this) && showToast) {
            showTip(getString(R.string.overlay_permission), false);
        }
        if (!isNotificationListenerEnabled() && showToast) {
            showTip(getString(R.string.notification_access_permission), false);
        }

        // 电视和手机走同一条路径：LedService 是唯一 WebSocket/悬浮窗宿主。
        Prefs.setEnabled(this, true);
        Prefs.setConnectionState(this, Prefs.CONNECTION_CONNECTING);
        LedService.start(this, Prefs.ACTION_START, "app");
        updateRunButton(true);
        updateStatus(isAlive());
        updateServerStatusCard();
        if (page == 0) renderHome();
        if (showToast) showTip(getString(R.string.toast_started), true);
    }

    private void requestStartupPermissions(boolean showToast) {
        // 只在用户主动点“启动”时申请，避免每次打开应用都弹一次系统权限框。
        if (!showToast) return;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 10);
            showTip(getString(R.string.notification_permission), false);
        }
    }

    private void stopClient() {
        // 先清用户意图，再停服务：即使 stopService 的 onDestroy 里发生意外，
        // 通知监听锚点也不会把“用户刚按下的停止”重新拉起来。
        Prefs.setEnabled(this, false);
        LedService.stop(this);
        updateRunButton(false);
        updateStatus(false);
        updateServerStatusCard();
        if (page == 0) renderHome();
        showTip(getString(R.string.toast_stopped), true);
    }

    private void updateRunButton(boolean enabled) {
        if (runButton == null) return;
        runButton.setText(enabled ? getString(R.string.stop) : getString(R.string.start));
        int color = enabled ? COLOR_RED : COLOR_GREEN;
        runButton.setTag(Integer.valueOf(color));
        // 用状态列表而不是只靠 onFocusChange：启动/停止按钮是 Button，自定义背景会盖掉
        // 系统默认焦点高亮；之前的白色描边贴在白色卡片上，电视上几乎看不见。
        setRunButtonBackground(color);
    }

    private void updateStatus(boolean alive) {
        if (statusDot == null || statusText == null) return;
        boolean enabled = Prefs.enabled(this);
        int color;
        int label;
        if (alive) {
            color = COLOR_GREEN;
            label = R.string.client_running;
        } else if (enabled) {
            // 用户开着但心跳过期：不是“已停止”，而是进程被系统杀掉、锚点正在恢复。
            color = COLOR_ACCENT;
            label = R.string.client_recovering;
        } else {
            color = COLOR_RED;
            label = R.string.client_stopped;
        }
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(color);
        setBackgroundCompat(statusDot, dotBg);
        statusText.setText(label);
        statusText.setTextColor(color);
    }

    private void updateServerStatusCard() {
        if (serverStatusTitle == null || serverStatusBadge == null) return;
        String state;
        if (!Prefs.enabled(this)) {
            state = Prefs.CONNECTION_DISCONNECTED;
        } else if (!Prefs.isHeartbeatFresh(this)) {
            state = Prefs.CONNECTION_CONNECTING;
        } else {
            state = Prefs.connectionState(this);
        }
        int color;
        int label;
        if (Prefs.CONNECTION_CONNECTED.equals(state)) {
            label = R.string.server_connected;
            color = COLOR_GREEN;
        } else if (Prefs.CONNECTION_CONNECTING.equals(state)) {
            label = R.string.server_connecting;
            color = COLOR_ACCENT;
        } else {
            label = R.string.server_disconnected;
            color = COLOR_RED;
        }
        serverStatusTitle.setText(R.string.connection_status);
        serverStatusBadge.setText(label);
        setRoundedBg(serverStatusBadge, color, dp(999));
    }

    private void setBootStart(boolean enabled) {
        try {
            ComponentName receiver = new ComponentName(this, BootReceiver.class);
            getPackageManager().setComponentEnabledSetting(
                    receiver,
                    enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            Prefs.store(this).edit()
                    .putString(Prefs.KEY_SERVER, serverInput == null
                            ? Prefs.server(this)
                            : cleanAddress(serverInput.getText().toString()))
                    .putBoolean(Prefs.KEY_BOOT, enabled)
                    .apply();
            showTip(getString(enabled ? R.string.toast_boot_enabled : R.string.toast_boot_disabled), true);
        } catch (Exception e) {
            Prefs.store(this).edit().putBoolean(Prefs.KEY_BOOT, false).apply();
            suppressBootChange = true;
            if (bootSwitch != null) bootSwitch.setChecked(false);
            suppressBootChange = false;
            showTip(getString(R.string.toast_boot_failed, e.getMessage()), false);
        }
    }

    private void openNotificationSettings() {
        if (Build.VERSION.SDK_INT >= 26) {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            if (startSettingsSafely(intent)) return;
        }
        settingsFallback();
    }

    private void openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= 23) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            if (startSettingsSafely(intent)) return;
        }
        settingsFallback();
    }

    private void openNotificationListenerSettings() {
        if (startSettingsSafely(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) return;
        settingsFallback();
    }

    /**
     * 系统设置页在电视固件上经常被裁掉（MiTV 实测三个页面都 resolve 不到）。
     * 旧实现把 ActivityNotFoundException 原文弹成“无法打开设置:No Activity found…”，
     * 用户看不懂。这里统一兜底：电视先试 AOSP TV 设置的应用管理页（里面有
     * App permissions / Special app access），再不行才跳到关于页的 ADB 命令卡片。
     */
    private void settingsFallback() {
        if (openAppManagementSettings()) {
            showTip(getString(R.string.toast_settings_app_management), false);
            return;
        }
        fallbackToDetailsOrHelp();
    }

    /** 权限卡片里的“应用管理 / 特殊权限”入口：打开成功就停在系统页，失败再给应用详情或 ADB。 */
    private void openAppManagementRow() {
        if (openAppManagementSettings()) return;
        fallbackToDetailsOrHelp();
    }

    private void fallbackToDetailsOrHelp() {
        if (isTv()) {
            showTip(getString(R.string.toast_settings_not_on_tv), false);
            showTvHelp();
        } else {
            showTip(getString(R.string.toast_settings_failed_app_details), false);
            openAppDetailsSettings();
        }
    }

    /**
     * 打开应用管理页。电视上优先走 AOSP TV 设置的 AppsActivity，
     * 它自带“App permissions / Special app access”，比厂商设置更容易找到悬浮窗和通知使用权。
     */
    private boolean openAppManagementSettings() {
        if (startSettingsSafely(new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))) return true;
        if (startSettingsSafely(new Intent().setComponent(new ComponentName(
                "com.android.tv.settings",
                "com.android.tv.settings.device.apps.AppsActivity")))) return true;
        // 小米自带设置（MiTV-MFTR0 实测）没有应用管理/特殊权限页，不能再把用户带过去，
        // 否则会误以为入口已经打开。这里直接失败，由调用方给出 ADB 命令。
        return false;
    }

    private boolean startSettingsSafely(Intent intent) {
        try {
            // 不能用 resolveActivity 做前置判断：Android 11+ 的包可见性会把它过滤成 null，
            // 手机上明明有设置页也会被误判。startActivity 对系统设置不受包可见性限制，
            // 页面不存在时抛 ActivityNotFoundException，交给 catch 兜底即可。
            startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void showTvHelp() {
        page = 2;
        renderCurrentPage();
        if (scrollView != null) {
            scrollView.post(new Runnable() {
                @Override public void run() {
                    scrollView.smoothScrollTo(0, contentArea == null ? 0 : contentArea.getHeight());
                }
            });
        }
    }

    private void openBatteryOptimizationSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 23 && !isTv()) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                    return;
                }
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } else {
                openAppDetailsSettings();
            }
        } catch (Exception e) {
            showTip(getString(R.string.toast_open_settings_failed, e.getMessage()), false);
            openAppDetailsSettings();
        }
    }

    private void openAppDetailsSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    // ═══════════════════════════════════════════════════════
    //  UI 组件工厂
    // ═══════════════════════════════════════════════════════

    /** 带阴影的卡片容器 */
    private LinearLayout cardContainer() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackgroundResource(R.drawable.card_bg);
        return card;
    }

    /** 卡片标题 */
    private TextView cardTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text != null ? text : "");
        tv.setTextColor(COLOR_TEXT);
        tv.setTextSize(17);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(2), 0, dp(10));
        return tv;
    }

    private LinearLayout sectionTitle(String title, String subtitle) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(COLOR_TEXT);
        t.setTextSize(17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        TextView s = new TextView(this);
        s.setText(subtitle);
        s.setTextColor(COLOR_MUTED);
        s.setTextSize(12);
        s.setPadding(0, dp(3), 0, 0);
        box.addView(t, fullWidth(-2, -2));
        box.addView(s, fullWidth(-2, -2));
        box.setPadding(0, 0, 0, dp(10));
        return box;
    }

    private Button compactButton(String text, int color) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(12);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setPadding(0, 0, 0, 0);
        setRoundedBg(button, color, dp(9));
        button.setTag(Integer.valueOf(color));
        button.setOnTouchListener(buttonTouch);
        makeFocusableAction(button);
        return button;
    }

    /** 分隔线 */
    private View divider() {
        View line = new View(this);
        line.setBackgroundColor(COLOR_BORDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        lp.bottomMargin = dp(10);
        line.setLayoutParams(lp);
        return line;
    }

    /** 输入框上方的标签 */
    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(COLOR_MUTED);
        tv.setTextSize(13);
        return tv;
    }

    /** 空间距 */
    private View spacer(int heightDp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(heightDp)));
        return v;
    }

    /** 全宽 LayoutParams 快捷方法 */
    private LinearLayout.LayoutParams fullWidth(int height, int minHeightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, height);
        if (minHeightDp > 0) lp.height = dp(minHeightDp);
        return lp;
    }

    /** 添加卡片到内容区（带底部间距） */
    private void addCard(View card) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(16);
        contentArea.addView(card, lp);
    }

    // ── 提示 Toast ──
    private void showTip(String message, boolean success) {
        TextView text = new TextView(this);
        text.setText(message);
        text.setTextColor(Color.WHITE);
        text.setTextSize(15);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setGravity(Gravity.CENTER);
        text.setPadding(dp(24), dp(14), dp(24), dp(14));
        setRoundedBg(text, success ? COLOR_GREEN : COLOR_RED, dp(12));
        Toast toast = new Toast(this);
        toast.setView(text);
        toast.setGravity(Gravity.CENTER, 0, 0);
        toast.setDuration(Toast.LENGTH_SHORT);
        toast.show();
    }

    private void makeFocusableAction(final View view) {
        view.setFocusable(true);
        view.setFocusableInTouchMode(false);
        view.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_UP && isConfirmKey(keyCode)) {
                    v.performClick();
                    return true;
                }
                return false;
            }
        });
        view.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                if (v == navHome || v == navLogs || v == navAbout) {
                    updateNavigation();
                } else {
                    applyActionFocusStyle(v, hasFocus);
                }
            }
        });
    }

    private void makeFocusablePanel(final View view) {
        view.setFocusable(true);
        view.setFocusableInTouchMode(false);
        view.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && (keyCode == KeyEvent.KEYCODE_DPAD_UP
                        || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                        || keyCode == KeyEvent.KEYCODE_PAGE_UP
                        || keyCode == KeyEvent.KEYCODE_PAGE_DOWN)) {
                    if (page == 1) pauseLogsAutoScroll();
                }
                return false;
            }
        });
        view.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(hasFocus ? Color.rgb(239, 246, 255) : Color.rgb(248, 250, 252));
                bg.setStroke(dp(hasFocus ? 2 : 1), hasFocus ? COLOR_ACCENT : Color.rgb(226, 232, 240));
                bg.setCornerRadius(dp(10));
                setBackgroundCompat(v, bg);
            }
        });
    }

    private void applyActionFocusStyle(View view, boolean focused) {
        // 启动/停止按钮的背景由 StateListDrawable 自己处理焦点态；
        // 这里再画一层会把状态列表覆盖掉，反而把蓝框弄丢。
        if (view == runButton) {
            view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(80).start();
            return;
        }
        Object tag = view.getTag();
        if (tag instanceof Integer) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(((Integer) tag).intValue());
            bg.setCornerRadius(dp(view == runButton ? 12 : 9));
            if (focused) bg.setStroke(dp(3), Color.WHITE);
            setBackgroundCompat(view, bg);
        } else {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(focused ? Color.rgb(239, 246, 255) : Color.TRANSPARENT);
            bg.setStroke(focused ? dp(2) : 0, focused ? COLOR_ACCENT : Color.TRANSPARENT);
            bg.setCornerRadius(dp(10));
            setBackgroundCompat(view, bg);
        }
        view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(80).start();
    }

    private void applyInputFocusStyle(View view, boolean focused) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_INPUT_BG);
        bg.setStroke(dp(focused ? 2 : 1), focused ? COLOR_ACCENT : Color.rgb(203, 213, 225));
        bg.setCornerRadius(dp(12));
        setBackgroundCompat(view, bg);
    }

    private boolean isConfirmKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    /**
     * 显式拉起输入法。
     *
     * <p>电视遥控器的“确定”键只在部分固件上等同于触摸点击，小米等机型按确定进入输入框
     * 不会自动弹 IME（实测 MiTV-MFTR0 / Android 11：焦点在 AutoCompleteTextView 上，
     * 按确定没有任何键盘出现）。showSoftInput 在 IME 连接尚未建立的首次点击时会返回 false，
     * 所以失败后延迟重试一次，仍失败再用 SHOW_FORCED 兜底。
     */
    private void showIme(final View view) {
        view.requestFocus();
        final InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm == null) return;
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
        // showSoftInput 的返回值不可靠：电视固件上既有返回 true 却没显示，也有返回 false 后
        // 才显示。稍后用 isActive 复核一次，没起来再强制请求。
        view.postDelayed(new Runnable() {
            @Override public void run() {
                if (!imm.isActive(view)) {
                    imm.showSoftInput(view, InputMethodManager.SHOW_FORCED);
                }
            }
        }, 150);
    }

    // ── 按钮触摸反馈 ──
    private final View.OnTouchListener buttonTouch = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.96f).scaleY(0.96f).alpha(0.85f).setDuration(80).start();
            } else if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start();
            }
            return false;
        }
    };

    // ═══════════════════════════════════════════════════════
    //  工具方法
    // ═══════════════════════════════════════════════════════

    /** 检测是否为电视设备：电视通常隐藏系统授权页，需要 ADB 兜底。 */
    private boolean isTv() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    /** 通知使用权是否授予。它是强停后的复活锚点，不是可选的通知权限。 */
    private boolean isNotificationListenerEnabled() {
        if (Build.VERSION.SDK_INT < 18) return false;
        String enabled = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        if (enabled == null || enabled.length() == 0) return false;
        // 这里用字符串比较而不是 LedNotifProbe.class：minSdk 14，
        // 在 API 18 以下加载 NotificationListenerService 子类会直接 NoClassDefFoundError。
        String targetClass = getPackageName() + ".LedNotifProbe";
        String[] services = enabled.split(":");
        for (int i = 0; i < services.length; i++) {
            ComponentName component = ComponentName.unflattenFromString(services[i]);
            if (component != null
                    && getPackageName().equals(component.getPackageName())
                    && targetClass.equals(component.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String cleanAddress(String raw) {
        String value = raw == null ? "" : raw.trim();
        String lower = value.toLowerCase(java.util.Locale.US);
        String prefix = "";
        int start = 0;
        if (lower.startsWith("https://")) { prefix = "https://"; start = 8; }
        else if (lower.startsWith("http://")) { prefix = "http://"; start = 7; }
        else if (lower.startsWith("wss://")) { prefix = "wss://"; start = 6; }
        else if (lower.startsWith("ws://")) { prefix = "ws://"; start = 5; }
        String authority = value.substring(start);
        int slash = authority.indexOf('/');
        if (slash >= 0) authority = authority.substring(0, slash);
        return prefix + authority;
    }

    private static String displayAddress(String raw) {
        String value = cleanAddress(raw);
        String lower = value.toLowerCase(java.util.Locale.US);
        if (lower.startsWith("https://")) return value.substring(8);
        if (lower.startsWith("http://")) return value.substring(7);
        if (lower.startsWith("wss://")) return value.substring(6);
        if (lower.startsWith("ws://")) return value.substring(5);
        return value;
    }

    private void setRoundedBg(View view, int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        setBackgroundCompat(view, drawable);
    }

    /** 启动/停止按钮：平时保持红/绿，获得遥控焦点时套一圈蓝色描边。 */
    private void setRunButtonBackground(int color) {
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(color);
        normal.setCornerRadius(dp(12));

        GradientDrawable focused = new GradientDrawable();
        focused.setColor(color);
        focused.setCornerRadius(dp(12));
        focused.setStroke(dp(3), COLOR_ACCENT);

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_focused}, focused);
        states.addState(new int[] {}, normal);
        setBackgroundCompat(runButton, states);
    }

    @SuppressWarnings("deprecation")
    private void setBackgroundCompat(View view, Drawable drawable) {
        if (Build.VERSION.SDK_INT >= 16) view.setBackground(drawable);
        else view.setBackgroundDrawable(drawable);
    }
}

package dev.dovie.yotocopy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.NfcA;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity implements NfcAdapter.ReaderCallback {
    private static final int BG = Color.rgb(16, 26, 35), PANEL = Color.rgb(26, 42, 53);
    private static final int INK = Color.rgb(238, 246, 248), MINT = Color.rgb(132, 227, 189);
    private static final int MUTED = Color.rgb(166, 186, 197), RED = Color.rgb(255, 166, 151);
    private final AtomicBoolean busy = new AtomicBoolean();
    private final Object stateLock = new Object();
    private final Set<String> protectedSources = new HashSet<>();
    private byte[] sourceMessage;
    private String sourceId = "", playlist = "", pendingId = "", replacementId = "";
    private String lastWritten = "";
    private int copies;
    private volatile boolean active;
    private NfcAdapter adapter;
    private TextView step, heading, detail;
    private Button next;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        protectedSources.addAll(getPreferences(MODE_PRIVATE).getStringSet("sources", new HashSet<>()));
        // Keep the current batch across rotation, without ever saving playlist signatures to disk.
        Object previous = getLastNonConfigurationInstance();
        if (previous instanceof Batch) {
            Batch b = (Batch) previous;
            sourceMessage = b.message; sourceId = b.uid; playlist = b.playlist;
            pendingId = b.pending; replacementId = b.replacement; lastWritten = b.last; copies = b.copies;
        }
        buildUi();
        adapter = NfcAdapter.getDefaultAdapter(this);
        if (adapter == null) show("NFC unavailable", "This phone needs NFC to copy cards.", true);
        else if (sourceMessage != null) show("Scan blank", "Hold a blank card against your phone.", false);
        else show("Scan original", "Hold the linked genuine MYO card against your phone.", false);
    }

    private static final class Batch {
        byte[] message; String uid, playlist, pending, replacement, last; int copies;
    }
    @Override public Object onRetainNonConfigurationInstance() {
        synchronized (stateLock) {
            Batch b = new Batch(); b.message = sourceMessage; b.uid = sourceId; b.playlist = playlist;
            b.pending = pendingId; b.replacement = replacementId; b.last = lastWritten; b.copies = copies;
            return b;
        }
    }
    @Override protected void onResume() {
        super.onResume(); active = true;
        if (adapter != null && adapter.isEnabled()) {
            if (heading.getText().toString().equals("Turn on NFC"))
                show(sourceMessage == null ? "Scan original" : "Scan blank",
                    sourceMessage == null ? "Hold the linked genuine MYO card against your phone." : "Hold a blank card against your phone.", false);
            enableReader();
        } else if (adapter != null) {
            show("Turn on NFC", "NFC is off.", true);
        }
    }
    private void enableReader() {
        if (!active || adapter == null || !adapter.isEnabled()) return;
        Bundle options = new Bundle();
        options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 150);
        adapter.enableReaderMode(this, this,
                NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
                | NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS, options);
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Samsung may reject onResume registration before the UID is foreground.
        if (hasFocus) enableReader();
    }
    @Override public void onTopResumedActivityChanged(boolean isTopResumedActivity) {
        super.onTopResumedActivityChanged(isTopResumedActivity);
        if (isTopResumedActivity) enableReader();
    }
    @Override protected void onPause() {
        active = false;
        if (adapter != null) adapter.disableReaderMode(this);
        super.onPause();
    }

    @Override public void onTagDiscovered(Tag tag) {
        if (!busy.compareAndSet(false, true)) return;
        runOnUiThread(() -> controls(false));
        try {
            synchronized (stateLock) {
                if (!active) return;
                NfcA nfc = NfcA.get(tag);
                if (nfc == null) throw new IOException("Use a MIFARE Ultralight EV1 card.");
                try {
                    nfc.connect(); nfc.setTimeout(1200);
                    TagMemory.requireSupported(nfc.transceive(new byte[]{0x60}));
                    String uid = hex(tag.getId());
                    byte[] header = read(nfc, 0), data = memory(nfc);
                    if (sourceMessage == null) {
                        byte[] message = TagMemory.message(data);
                        if (message == null) throw new IOException("Scan the genuine MYO card first.");
                        NdefRecord[] records = new NdefMessage(message).getRecords();
                        Uri uri = records.length == 1 ? records[0].toUri() : null;
                        if (uri == null || !"https".equals(uri.getScheme()) || !"yoto.io".equalsIgnoreCase(uri.getHost())
                                || uri.getPath() == null || !uri.getPath().matches("/[A-Za-z0-9]+"))
                            throw new IOException("Scan a card with a single https://yoto.io playlist link.");
                        TagMemory.pack(message); // Check capacity before arming a destination write.
                        sourceMessage = message.clone(); sourceId = uid; playlist = "yoto.io" + uri.getPath();
                        protectedSources.add(uid);
                        getPreferences(MODE_PRIVATE).edit().putStringSet("sources", new HashSet<>(protectedSources)).apply();
                        vibrate(70);
                        show("Scan blank", "Set the original aside. Hold a blank card here.", false);
                        return;
                    }
                    if (protectedSources.contains(uid)) {
                        show("Scan blank", "That's the original. Use a blank card.", false);
                        return;
                    }
                    TagMemory.requireWritable(header);
                    byte[] packed = TagMemory.pack(sourceMessage);
                    if (Arrays.equals(packed, data) && Arrays.equals(Arrays.copyOfRange(header, 12, 16), TagMemory.CC)) {
                        show("Copied", "This card already has the playlist.", false);
                        return;
                    }
                    if (!TagMemory.blank(data) && !uid.equals(pendingId) && !uid.equals(replacementId)) {
                        askReplace(uid);
                        return;
                    }
                    pendingId = uid;
                    show("Copying…", "Keep the card still.", false);
                    // Invalidate NDEF length first; commit the first page only after the rest is written.
                    write(nfc, 4, new byte[]{3, 0, 0, 0});
                    for (int page = 5; page <= 15; page++)
                        write(nfc, page, Arrays.copyOfRange(packed, (page - 4) * 4, (page - 3) * 4));
                    write(nfc, 4, Arrays.copyOfRange(packed, 0, 4));
                    // Only blank capability containers are initialized; never touch UID, lock, or config pages.
                    if (!Arrays.equals(Arrays.copyOfRange(header, 12, 16), TagMemory.CC)) write(nfc, 3, TagMemory.CC);
                    if (!Arrays.equals(memory(nfc), packed) || !Arrays.equals(Arrays.copyOfRange(read(nfc, 0), 12, 16), TagMemory.CC))
                        throw new IOException("Read-back did not match. Hold this same card here again to retry.");
                    if (!uid.equals(lastWritten)) copies++;
                    lastWritten = uid; pendingId = ""; replacementId = "";
                    vibrate(160);
                    show("Copied", "Remove this card.", false);
                } finally { try { nfc.close(); } catch (IOException ignored) { } }
            }
        } catch (Exception e) {
            vibrate(250);
            String message = e.getMessage();
            show("Try again", (message == null || message.trim().isEmpty()
                    ? "Hold the same card steadily against your phone." : message), true);
        } finally {
            busy.set(false);
            runOnUiThread(() -> controls(true));
        }
    }
    private byte[] read(NfcA nfc, int page) throws IOException {
        byte[] b = nfc.transceive(new byte[]{0x30, (byte) page});
        if (b.length != 16) throw new IOException("Could not read the card. Keep it steady and try again.");
        return b;
    }
    private byte[] memory(NfcA nfc) throws IOException {
        byte[] out = new byte[48];
        for (int page = 4; page <= 12; page += 4) System.arraycopy(read(nfc, page), 0, out, (page - 4) * 4, 16);
        return out;
    }
    private void write(NfcA nfc, int page, byte[] bytes) throws IOException {
        byte[] cmd = new byte[6]; cmd[0] = (byte) 0xA2; cmd[1] = (byte) page;
        System.arraycopy(bytes, 0, cmd, 2, 4);
        byte[] response = nfc.transceive(cmd);
        if (response.length != 1 || (response[0] & 15) != 10)
            throw new IOException("The card refused a write. Keep this card for a retry.");
    }
    private void askReplace(String uid) {
        runOnUiThread(() -> {
            if (!active || isFinishing()) return;
            new AlertDialog.Builder(this).setTitle("Replace this card?")
                .setMessage("Its current playlist will be overwritten.")
                .setNegativeButton("Cancel", (d, w) -> show("Scan blank", "Use a blank card instead.", false))
                .setPositiveButton("Replace", (d, w) -> {
                    synchronized (stateLock) { replacementId = uid; }
                    show("Scan that card again", "Lift it away, then hold it here again.", false);
                }).show();
        });
    }
    private void reset() {
        if (busy.get()) return;
        synchronized (stateLock) {
            sourceMessage = null; sourceId = ""; playlist = ""; pendingId = ""; replacementId = "";
        }
        show("Scan original", "Hold the linked genuine MYO card against your phone.", false);
    }
    private void vibrate(long ms) {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
    }
    private String hex(byte[] bytes) {
        StringBuilder b = new StringBuilder();
        for (byte v : bytes) b.append(String.format(java.util.Locale.ROOT, "%02X", v & 255));
        return b.toString();
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(0, dp(8), 0, dp(8)); return t;
    }
    private GradientDrawable background(int color) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(24)); return d;
    }
    private Button button(String value, View.OnClickListener listener) {
        Button b = new Button(this); b.setText(value); b.setAllCaps(false); b.setTextSize(16);
        b.setTextColor(BG); b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(MINT));
        b.setOnClickListener(listener); return b;
    }
    private void buildUi() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        scroll.addView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            root.setPadding(dp(24) + bars.left, dp(20) + bars.top, dp(24) + bars.right, dp(20) + bars.bottom);
            return insets;
        });
        TextView title = text("Card copier", 16, MUTED, false);
        title.setGravity(Gravity.CENTER); root.addView(title);
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        root.addView(card, new LinearLayout.LayoutParams(-1, 0, 1));
        step = text("1", 44, MINT, true); step.setGravity(Gravity.CENTER);
        GradientDrawable circle = background(PANEL); circle.setShape(GradientDrawable.OVAL); step.setBackground(circle);
        LinearLayout.LayoutParams circleParams = new LinearLayout.LayoutParams(dp(112), dp(112));
        circleParams.setMargins(0, dp(32), 0, dp(28)); card.addView(step, circleParams);
        heading = text("", 32, INK, true); heading.setGravity(Gravity.CENTER);
        heading.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); card.addView(heading);
        detail = text("", 17, MUTED, false); detail.setGravity(Gravity.CENTER); card.addView(detail);
        next = button("Next playlist", v -> {
            if (adapter != null && !adapter.isEnabled()) startActivity(new Intent(Settings.ACTION_NFC_SETTINGS));
            else reset();
        });
        root.addView(next, new LinearLayout.LayoutParams(-1, dp(56)));
        setContentView(scroll);
    }
    private void controls(boolean enabled) {
        next.setEnabled(enabled);
    }
    private void show(String title, String body, boolean error) {
        runOnUiThread(() -> {
            if (isDestroyed()) return;
            heading.setText(title);
            boolean success = title.equals("Copied");
            heading.setTextColor(error ? RED : success ? MINT : INK);
            detail.setText(body);
            step.setText(error ? "!" : success ? "✓" : sourceMessage == null ? "1" : "2");
            step.setTextColor(error ? RED : MINT);
            boolean nfcOff = adapter != null && !adapter.isEnabled();
            next.setText(nfcOff ? "Turn on NFC" : "Next playlist");
            next.setVisibility(sourceMessage != null || nfcOff ? View.VISIBLE : View.INVISIBLE);
        });
    }
}

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
    private boolean active;
    private NfcAdapter adapter;
    private TextView phase, heading, detail, source, count;
    private Button next, yoto, replace;

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
        else if (sourceMessage != null) show("Tap a blank card", "Your playlist is ready. Hold the destination steady until it turns green.", false);
        else show("Tap your genuine card", "Link your playlist in Yoto first, then hold the genuine MYO card against this phone.", false);
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
            Bundle options = new Bundle(); options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 150);
            adapter.enableReaderMode(this, this,
                    NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
                    | NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS, options);
        } else if (adapter != null) {
            show("Turn on NFC", "Enable NFC in phone settings, then return here.", true);
        }
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
                        if (message == null) throw new IOException("That card is blank. Scan the linked genuine MYO card first.");
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
                        show("Now tap a blank card", "Source captured. Set the genuine card aside and hold a different blank card here.", false);
                        return;
                    }
                    if (protectedSources.contains(uid)) {
                        show("Genuine card protected", "This is a source card. Set it aside and tap a different blank card.", false);
                        return;
                    }
                    TagMemory.requireWritable(header);
                    byte[] packed = TagMemory.pack(sourceMessage);
                    if (Arrays.equals(packed, data) && Arrays.equals(Arrays.copyOfRange(header, 12, 16), TagMemory.CC)) {
                        show("Already verified", "This card already contains the selected playlist. Tap another blank or choose Next playlist.", false);
                        return;
                    }
                    if (!TagMemory.blank(data) && !uid.equals(pendingId) && !uid.equals(replacementId)) {
                        askReplace(uid);
                        return;
                    }
                    pendingId = uid;
                    show("Writing… keep it still", "Formatting, writing, and checking your card in one connection.", false);
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
                    show("Copied & verified", "Remove this card. Tap another blank for the same playlist, or choose Next playlist.", false);
                } finally { try { nfc.close(); } catch (IOException ignored) { } }
            }
        } catch (Exception e) {
            vibrate(250);
            String message = e.getMessage();
            show("Try that card again", (message == null || message.trim().isEmpty()
                    ? "Contact was lost. Hold the same card steadily against the NFC antenna." : message)
                    + (pendingId.isEmpty() ? "" : "\nKeep the same destination card for the retry."), true);
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
            new AlertDialog.Builder(this).setTitle("This card already has content")
                .setMessage("Replace the contents of card …" + uid.substring(Math.max(0, uid.length() - 6))
                        + " with " + playlist + "? Its current playlist link will be overwritten.")
                .setNegativeButton("Keep existing card", (d, w) -> show("Tap a blank card", "The existing card was left unchanged.", false))
                .setPositiveButton("Replace on next tap", (d, w) -> {
                    synchronized (stateLock) { replacementId = uid; }
                    show("Tap that same card again", "Only the selected card is approved for replacement. Source cards remain protected.", false);
                }).show();
        });
    }
    private void reset() {
        if (busy.get()) return;
        synchronized (stateLock) {
            sourceMessage = null; sourceId = ""; playlist = ""; pendingId = ""; replacementId = "";
        }
        show("Tap your genuine card", "Link the next playlist in Yoto, then scan the genuine MYO card here.", false);
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
        root.addView(text("YOTO CARD COPIER", 13, MINT, true));
        root.addView(text("Two taps.\nOne new card.", 36, INK, true));
        root.addView(text("Read the genuine MYO, then tap a blank.\nFormatting and verification happen automatically.", 16, MUTED, false));
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(22), dp(22), dp(22)); card.setBackground(background(PANEL));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2); cp.setMargins(0, dp(24), 0, dp(16)); root.addView(card, cp);
        phase = text("01 / READ SOURCE", 12, MINT, true); card.addView(phase);
        heading = text("", 25, INK, true); heading.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); card.addView(heading);
        detail = text("", 16, MUTED, false); card.addView(detail);
        source = text("", 14, MINT, false); card.addView(source);
        count = text("", 14, MUTED, false); root.addView(count);
        next = button("Next playlist", v -> reset()); root.addView(next, new LinearLayout.LayoutParams(-1, dp(56)));
        yoto = button("Open Yoto", v -> {
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage("com.yotoplay.yoto");
            try { startActivity(intent); } catch (Exception e) { show("Open Yoto yourself", "Link the next playlist, then return here.", false); }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(56)); bp.setMargins(0, dp(12), 0, 0); root.addView(yoto, bp);
        replace = button("NFC settings", v -> startActivity(new Intent(Settings.ACTION_NFC_SETTINGS)));
        root.addView(replace, bp);
        TextView note = text("Play the genuine card briefly on your Yoto to queue the audio. Generic cards may need the audio downloaded before playback.\n\nOffline app · no account · no network access", 13, MUTED, false);
        note.setPadding(0, dp(24), 0, 0); root.addView(note);
        setContentView(scroll);
    }
    private void controls(boolean enabled) {
        next.setEnabled(enabled); yoto.setEnabled(enabled); replace.setEnabled(enabled);
    }
    private void show(String title, String body, boolean error) {
        runOnUiThread(() -> {
            if (isDestroyed()) return;
            heading.setText(title);
            heading.setTextColor(error ? RED : title.equals("Copied & verified") || title.equals("Already verified") ? MINT : INK);
            detail.setText(body);
            phase.setText(sourceMessage == null ? "01 / READ SOURCE" : "02 / WRITE & VERIFY");
            source.setText(playlist.isEmpty() ? "Source cards are never overwritten." : playlist + " · " + sourceMessage.length + " bytes");
            count.setText(copies + (copies == 1 ? " card copied this session" : " cards copied this session"));
        });
    }
}

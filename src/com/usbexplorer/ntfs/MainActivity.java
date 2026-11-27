package com.usbexplorer.ntfs;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import com.usbexplorer.ntfs.core.Disk;
import com.usbexplorer.ntfs.core.Groups;
import com.usbexplorer.ntfs.core.MediaType;
import com.usbexplorer.ntfs.core.MftIndex;
import com.usbexplorer.ntfs.core.Ntfs;
import com.usbexplorer.ntfs.core.Partitions;
import com.usbexplorer.ntfs.core.Search;
import com.usbexplorer.ntfs.core.Suggester;
import com.usbexplorer.ntfs.core.Util;

import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements AudioPlayer.Listener {
    private static final String ACTION_PERMISSION = "com.usbexplorer.ntfs.USB_PERMISSION";
    private static final String PREFS = "ajustes", KEY_PIN = "fijar";

    private static final String POWER_HINT =
            "Si el disco gira pero da error o no aparece, lo más probable es que le falte energía: "
            + "usa un hub USB-C con carga (PD) conectado al cargador.";
    private static final String NO_DEVICE =
            "No se detecta ningún dispositivo USB.\n\n"
            + "• Comprueba que el adaptador OTG esté bien conectado.\n"
            + "• Si el disco gira pero no aparece aquí, casi seguro le falta energía: usa un hub USB-C con carga (PD).\n"
            + "• Prueba con un pendrive para descartar el adaptador.";

    // tipos de pantalla
    private static final int FOLDER = 0, VIDEOS = 1, PHOTOS = 2, MUSIC = 3, GROUPS = 4, GROUP = 5, SEARCH = 6, BUCKET = 7, COVER = 8;

    /** Una fila/mosaico: un archivo, un grupo de archivos (carpeta, mes...) o un grupo de personas. */
    private static final class Row {
        Ntfs.Entry e;
        String title;
        List<Ntfs.Entry> items;
        Groups.Group group;
    }

    /** Una pantalla de la pila de navegación. */
    private static final class Screen {
        int kind;
        String title = "", subtitle = "";
        long folder = Ntfs.ROOT;
        Groups.Group group;
        MediaType filter;                       // pantalla de grupo: Todo / Vídeos / Fotos / Música
        List<Ntfs.Entry> base = new ArrayList<Ntfs.Entry>();
        Search.Sort sort = Search.Sort.NAME;
        boolean desc;
        Search.GroupBy groupBy = Search.GroupBy.NONE;
        boolean grid = true;
        List<Row> rows = new ArrayList<Row>();
        int scroll;
    }

    private UsbManager usb;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ArrayList<Screen> stack = new ArrayList<Screen>();
    private boolean opening;
    private boolean showHidden;
    private Search lastSearch = new Search();
    private final Set<Long> selected = new LinkedHashSet<Long>();
    private boolean selecting;

    // vistas
    private LinearLayout nav, toolbar, selBar, nowBar;
    private TextView titleView, subtitleView, status, selCount, nowTitle, nowSub, nowPlay, diskLabel;
    private ImageView nowCover;
    private SeekBar nowSeek;
    private GridView grid;
    private TileAdapter adapter;
    private final List<TextView> navItems = new ArrayList<TextView>();
    private int navSelected = -1;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            String a = i.getAction();
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                Session s = Session.current;
                if (s != null && (d == null || d.getDeviceName().equals(s.usb.device.getDeviceName()))) {
                    CrashLog.event("El disco USB se desconectó sin expulsar (¿falta de energía?)");
                    Session.lost();
                    afterDiskGone();
                    Ui.show(Ui.dialog(MainActivity.this).setTitle("Disco desconectado")
                            .setMessage("El disco se desconectó sin expulsarlo.\n\nComo esta app solo lee y nunca escribe, "
                                    + "tus archivos no se han modificado. Aun así, usa siempre «Expulsar disco» para "
                                    + "detener el motor antes de desconectarlo.")
                            .setPositiveButton("Entendido", null));
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                scan();
            } else if (ACTION_PERMISSION.equals(a)) {
                if (d != null && usb.hasPermission(d)) openDevice(d);
                else message("Sin permiso no puedo leer el disco. Pulsa «Buscar disco» para intentarlo otra vez.");
            }
        }
    };

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.secure(this);
        CrashLog.install(this);
        usb = getSystemService(UsbManager.class);
        buildUi();
        showLastCrash();
        IntentFilter f = new IntentFilter(ACTION_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(receiver, f);
        AudioPlayer.get().addListener(this);
        if (Session.current != null) onMounted(); else noDiskScreen();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (Session.current == null) scan();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AudioPlayer.get().removeListener(this);
        unregisterReceiver(receiver);
        ui.removeCallbacksAndMessages(null);
        if (isFinishing() && Session.current != null) {
            io.execute(new Runnable() { @Override public void run() { Session.eject(); } });
        }
        io.shutdown();
    }

    @Override
    public void onBackPressed() {
        if (selecting) { endSelection(); return; }
        if (stack.size() > 1) {
            stack.remove(stack.size() - 1);
            render();
            return;
        }
        if (Session.current != null) {
            Ui.show(Ui.dialog(this).setTitle("Primero expulsa el disco")
                    .setMessage("Para proteger el disco, la app no se cierra mientras esté conectado.")
                    .setPositiveButton("Expulsar y salir", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { eject(true); }
                    })
                    .setNegativeButton("Cancelar", null));
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ interfaz

    private int dp(float v) { return Ui.dp(this, v); }

    private void buildUi() {
        boolean wide = getResources().getConfiguration().screenWidthDp >= 600;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.HORIZONTAL);

        // menú lateral
        nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.VERTICAL);
        nav.setBackgroundColor(Ui.NAV);
        nav.setPadding(dp(8), dp(16), dp(8), dp(12));
        TextView app = Ui.text(this, wide ? "Mi disco" : "💽", wide ? 22 : 24, Ui.TEXT, Ui.LIGHT);
        app.setPadding(dp(10), 0, 0, dp(2));
        nav.addView(app);
        diskLabel = Ui.text(this, "", 12, Ui.SUB, null);
        diskLabel.setPadding(dp(10), 0, 0, dp(16));
        if (!wide) diskLabel.setVisibility(View.GONE);
        nav.addView(diskLabel);
        String[][] items = {{"📁", "Carpetas"}, {"🎬", "Vídeos"}, {"🖼", "Fotos"}, {"🎵", "Música"},
                {"👤", "Artistas y grupos"}, {"🔍", "Buscar"}};
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            TextView t = navItem(items[i][0], items[i][1], wide);
            t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { navigate(idx); } });
            navItems.add(t);
            nav.addView(t);
        }
        View spacer = new View(this);
        nav.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        final TextView pin = navItem("📌", "Fijar app", wide);
        refreshPin(pin);
        pin.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
                sp.edit().putBoolean(KEY_PIN, !sp.getBoolean(KEY_PIN, true)).apply();
                refreshPin(pin);
                if (Session.current != null) applyPin();
            }
        });
        nav.addView(pin);
        TextView ej = navItem("⏏", "Expulsar disco", wide);
        ej.setTextColor(0xFFFF8A80);
        ej.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { eject(false); } });
        nav.addView(ej);
        main.addView(nav, new LinearLayout.LayoutParams(wide ? dp(230) : dp(64), -1));

        // contenido
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(18), dp(16), 0);
        titleView = Ui.text(this, "", 32, Ui.TEXT, Ui.LIGHT);
        titleView.setSingleLine(true);
        subtitleView = Ui.text(this, "", 14, Ui.SUB, null);
        subtitleView.setSingleLine(true);
        content.addView(titleView);
        content.addView(subtitleView);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        toolbar = new LinearLayout(this);
        toolbar.setPadding(0, dp(10), 0, dp(6));
        hs.addView(toolbar);
        content.addView(hs);
        selBar = new LinearLayout(this);
        selBar.setGravity(Gravity.CENTER_VERTICAL);
        selBar.setBackground(Ui.round(Ui.ACCENT, dp(4)));
        selBar.setPadding(dp(12), dp(4), dp(4), dp(4));
        selBar.setVisibility(View.GONE);
        content.addView(selBar);
        status = Ui.text(this, "", 15, Ui.SUB, null);
        status.setPadding(0, dp(6), 0, dp(6));
        content.addView(status);
        grid = new GridView(this);
        grid.setVerticalSpacing(dp(14));
        grid.setHorizontalSpacing(dp(14));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        grid.setPadding(0, dp(6), 0, dp(16));
        grid.setClipToPadding(false);
        adapter = new TileAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) { onTap(adapter.getItem(pos)); }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                onLongTap(adapter.getItem(pos));
                return true;
            }
        });
        content.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));
        main.addView(content, new LinearLayout.LayoutParams(0, -1, 1f));
        root.addView(main, new LinearLayout.LayoutParams(-1, 0, 1f));

        // barra "Reproduciendo"
        nowBar = new LinearLayout(this);
        nowBar.setOrientation(LinearLayout.VERTICAL);
        nowBar.setBackgroundColor(Ui.PANEL);
        nowSeek = new SeekBar(this);
        nowSeek.setMax(1000);
        nowSeek.setPadding(0, 0, 0, 0);
        nowSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) { }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { AudioPlayer.get().seek(s.getProgress() / 1000f); }
        });
        nowBar.addView(nowSeek, new LinearLayout.LayoutParams(-1, dp(18)));
        LinearLayout nowRow = new LinearLayout(this);
        nowRow.setGravity(Gravity.CENTER_VERTICAL);
        nowRow.setPadding(dp(12), dp(4), dp(12), dp(8));
        nowCover = new ImageView(this);
        nowCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        nowCover.setBackground(Ui.round(Ui.ACCENT, dp(2)));
        nowRow.addView(nowCover, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout nt = new LinearLayout(this);
        nt.setOrientation(LinearLayout.VERTICAL);
        nt.setPadding(dp(12), 0, dp(12), 0);
        nowTitle = Ui.text(this, "", 15, Ui.TEXT, Ui.MEDIUM);
        nowTitle.setSingleLine(true);
        nowSub = Ui.text(this, "", 12, Ui.SUB, null);
        nowSub.setSingleLine(true);
        nt.addView(nowTitle);
        nt.addView(nowSub);
        nowRow.addView(nt, new LinearLayout.LayoutParams(0, -2, 1f));
        nowRow.addView(barButton("🔀", new View.OnClickListener() {
            @Override public void onClick(View v) { AudioPlayer.get().setShuffle(!AudioPlayer.get().shuffle()); toast(AudioPlayer.get().shuffle() ? "Aleatorio activado" : "Aleatorio desactivado"); }
        }));
        nowRow.addView(barButton("⏮", new View.OnClickListener() { @Override public void onClick(View v) { AudioPlayer.get().prev(); } }));
        nowPlay = barButton("⏸", new View.OnClickListener() { @Override public void onClick(View v) { AudioPlayer.get().toggle(); } });
        nowRow.addView(nowPlay);
        nowRow.addView(barButton("⏭", new View.OnClickListener() { @Override public void onClick(View v) { AudioPlayer.get().next(); } }));
        nowRow.addView(barButton("✕", new View.OnClickListener() { @Override public void onClick(View v) { AudioPlayer.get().stopAll(); } }));
        nowBar.addView(nowRow);
        nowBar.setVisibility(View.GONE);
        root.addView(nowBar, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    private TextView navItem(String icon, String label, boolean wide) {
        TextView t = Ui.text(this, wide ? icon + "    " + label : icon, wide ? 16 : 22, Ui.TEXT, null);
        t.setPadding(dp(12), dp(11), dp(12), dp(11));
        t.setSingleLine(true);
        t.setGravity(wide ? Gravity.CENTER_VERTICAL : Gravity.CENTER);
        t.setBackground(Ui.pressable(Color.TRANSPARENT, 0xFF333333, dp(4)));
        t.setClickable(true);
        return t;
    }

    private TextView barButton(String s, View.OnClickListener l) {
        TextView t = Ui.button(this, s, Color.TRANSPARENT);
        t.setTextSize(20);
        t.setOnClickListener(l);
        return t;
    }

    private TextView toolButton(String s, View.OnClickListener l) {
        TextView t = Ui.button(this, s, Ui.CARD);
        t.setTextSize(14);
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private void refreshPin(TextView t) {
        boolean on = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_PIN, true);
        String label = on ? "Fijar app: sí" : "Fijar app: no";
        boolean wide = getResources().getConfiguration().screenWidthDp >= 600;
        t.setText(wide ? "📌    " + label : "📌");
        t.setAlpha(on ? 1f : 0.5f);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private void message(String s) {
        status.setVisibility(View.VISIBLE);
        status.setText(s);
    }

    private void post(Runnable r) { ui.post(r); }

    /**
     * Si la app se cerró por un error la última vez, muestra el motivo técnico. Este diálogo SÍ
     * permite captura de pantalla (no contiene nombres de archivos) para poder enviarlo.
     */
    private void showLastCrash() {
        final String r = CrashLog.lastReport(this);
        if (r.isEmpty()) return;
        TextView t = Ui.text(this, r, 11, Ui.TEXT, android.graphics.Typeface.MONOSPACE);
        t.setTextIsSelectable(false);
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("La app se cerró inesperadamente la última vez")
                .setView(sv)
                .setPositiveButton("Cerrar", null)
                .show();   // sin FLAG_SECURE a propósito: haz una captura y envíala para el diagnóstico
    }

    // ------------------------------------------------------------------ conexión del disco

    private void noDiskScreen() {
        stack.clear();
        setNav(-1);
        titleView.setText("Conecta tu disco");
        subtitleView.setText("Solo lectura · sin Internet · sin capturas");
        toolbar.removeAllViews();
        toolbar.addView(toolButton("🔌  Buscar disco", new View.OnClickListener() { @Override public void onClick(View v) { scan(); } }));
        adapter.set(new ArrayList<Row>(), true);
        diskLabel.setText("");
        scan();
    }

    private void scan() {
        if (opening || Session.current != null) return;
        UsbDevice target = null;
        StringBuilder sb = new StringBuilder();
        for (UsbDevice d : usb.getDeviceList().values()) {
            sb.append("• ").append(describe(d)).append('\n');
            if (target == null && UsbMassStorage.findInterface(d) != null) target = d;
        }
        if (target == null) {
            message(sb.length() == 0 ? NO_DEVICE
                    : "Hay un dispositivo USB conectado, pero no es un disco compatible:\n\n" + sb + "\n" + POWER_HINT);
            return;
        }
        if (!usb.hasPermission(target)) {
            message("Disco detectado. Acepta el permiso que aparece en pantalla para que la app pueda leerlo.");
            Intent i = new Intent(ACTION_PERMISSION).setPackage(getPackageName());
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            usb.requestPermission(target, PendingIntent.getBroadcast(this, 0, i, flags));
            return;
        }
        openDevice(target);
    }

    private static String describe(UsbDevice d) {
        String name = null;
        try {
            String m = d.getManufacturerName(), p = d.getProductName();
            name = ((m != null ? m + " " : "") + (p != null ? p : "")).trim();
        } catch (Exception ignored) {
        }
        StringBuilder cls = new StringBuilder();
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            if (i > 0) cls.append(',');
            cls.append(d.getInterface(i).getInterfaceClass());
        }
        return String.format("%04X:%04X %s (clase %s)", d.getVendorId(), d.getProductId(), name == null ? "" : name, cls);
    }

    private void openDevice(final UsbDevice d) {
        if (opening || Session.current != null) return;
        opening = true;
        message("Conectando con el disco… Un disco mecánico puede tardar unos segundos en arrancar.");
        io.execute(new Runnable() {
            @Override public void run() {
                UsbMassStorage ms = null;
                try {
                    ms = UsbMassStorage.open(usb, d);
                    final Disk disk = new Disk(ms);
                    final List<Partitions.Part> parts = Partitions.scan(disk);
                    final String info = (ms.vendor + " " + ms.product).trim() + " · " + Util.humanSize(disk.size());
                    final List<Partitions.Part> ntfs = new ArrayList<Partitions.Part>();
                    boolean other = false;
                    for (Partitions.Part p : parts) {
                        if (p.isNtfs()) ntfs.add(p); else if (!"?".equals(p.fs)) other = true;
                    }
                    if (ntfs.isEmpty()) {
                        ms.stopAndEject();
                        StringBuilder sb = new StringBuilder("El disco (" + info + ") no tiene particiones NTFS.\n\n");
                        for (Partitions.Part p : parts) sb.append("• ").append(p).append('\n');
                        if (other) sb.append("\nEse formato Android ya lo lee directamente con «Mis archivos».");
                        final String m = sb.toString();
                        post(new Runnable() { @Override public void run() { opening = false; message(m); } });
                        return;
                    }
                    final UsbMassStorage fms = ms;
                    post(new Runnable() {
                        @Override public void run() {
                            if (ntfs.size() == 1) mount(fms, disk, ntfs.get(0), info);
                            else choosePartition(fms, disk, ntfs, info);
                        }
                    });
                } catch (final IOException e) {
                    if (ms != null) ms.close();
                    post(new Runnable() {
                        @Override public void run() {
                            opening = false;
                            message("No se pudo leer el disco:\n" + e.getMessage() + "\n\n" + POWER_HINT);
                        }
                    });
                }
            }
        });
    }

    private void choosePartition(final UsbMassStorage ms, final Disk disk, final List<Partitions.Part> parts, final String info) {
        String[] items = new String[parts.size()];
        for (int i = 0; i < items.length; i++) items[i] = parts.get(i).toString();
        Ui.show(Ui.dialog(this).setTitle("Elige la partición").setCancelable(false)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int w) { mount(ms, disk, parts.get(w), info); }
                }));
    }

    private void mount(final UsbMassStorage ms, final Disk disk, final Partitions.Part part, final String info) {
        message("Leyendo NTFS…");
        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    Ntfs fs = new Ntfs(disk, part.offset);
                    Session s = new Session(ms, disk, fs, info);
                    s.loadGroups(getApplicationContext());
                    Session.current = s;
                    CrashLog.event("Disco montado: " + s.info + ", clúster " + fs.clusterSize + " B, sector " + disk.blockSize() + " B");
                    post(new Runnable() { @Override public void run() { opening = false; onMounted(); } });
                } catch (final IOException e) {
                    ms.stopAndEject();
                    post(new Runnable() {
                        @Override public void run() { opening = false; message("No se pudo leer la partición NTFS:\n" + e.getMessage()); }
                    });
                }
            }
        });
    }

    private void onMounted() {
        Session s = Session.current;
        diskLabel.setText((s.fs.label().isEmpty() ? "Disco NTFS" : s.fs.label()) + "\n" + s.info);
        startService(new Intent(this, DiskService.class));
        applyPin();
        navigate(0);
        ensureIndex(null, false);
    }

    private void applyPin() {
        boolean on = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_PIN, true);
        try {
            if (on) startLockTask(); else stopLockTask();
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ expulsión segura

    private void eject(final boolean exitAfter) {
        if (Session.current == null) {
            if (exitAfter) finish(); else toast("No hay ningún disco conectado");
            return;
        }
        final ProgressDialog pd = new ProgressDialog(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        pd.setMessage("Deteniendo el disco de forma segura…");
        pd.setCancelable(false);
        Ui.show(pd);
        io.execute(new Runnable() {
            @Override public void run() {
                final boolean ok = Session.eject();
                post(new Runnable() {
                    @Override public void run() {
                        try { pd.dismiss(); } catch (Exception ignored) { }
                        afterDiskGone();
                        Ui.show(Ui.dialog(MainActivity.this).setTitle(ok ? "✅ Disco detenido" : "Conexión cerrada")
                                .setMessage(ok ? "El disco se ha detenido y ya puedes desconectarlo con seguridad."
                                        : "El disco no confirmó la parada. Espera unos 10 segundos a que deje de girar antes de desconectarlo.")
                                .setCancelable(false)
                                .setPositiveButton("Aceptar", new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int w) { if (exitAfter) finish(); }
                                }));
                    }
                });
            }
        });
    }

    private void afterDiskGone() {
        stopService(new Intent(this, DiskService.class));
        try { stopLockTask(); } catch (Exception ignored) { }
        selecting = false;
        selected.clear();
        selBar.setVisibility(View.GONE);
        onPlayerChanged();
        noDiskScreen();
        // cierra visor/reproductor si estaban abiertos encima
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    // ------------------------------------------------------------------ índice

    private boolean indexing;
    private final List<Runnable> afterIndex = new ArrayList<Runnable>();

    /** Construye el índice del disco (una vez) y luego ejecuta {@code then}. */
    private void ensureIndex(Runnable then, final boolean showProgress) {
        final Session s = Session.current;
        if (s == null) return;
        if (s.index != null) { if (then != null) then.run(); return; }
        if (then != null) afterIndex.add(then);
        if (indexing) { if (showProgress) message("Preparando el índice del disco…"); return; }
        indexing = true;
        final boolean[] cancel = {false};
        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    s.index(new MftIndex.Progress() {
                        long last;
                        @Override public void update(final long done, final long total) {
                            long now = System.currentTimeMillis();
                            if (now - last < 250) return;
                            last = now;
                            post(new Runnable() {
                                @Override public void run() {
                                    if (!afterIndex.isEmpty()) message("Indexando el disco… " + (total == 0 ? 0 : done * 100 / total) + " %");
                                }
                            });
                        }
                        @Override public boolean cancelled() { return cancel[0] || s.isClosed(); }
                    });
                    post(new Runnable() {
                        @Override public void run() {
                            indexing = false;
                            List<Runnable> l = new ArrayList<Runnable>(afterIndex);
                            afterIndex.clear();
                            if (Session.current == s) for (Runnable r : l) r.run();
                        }
                    });
                } catch (final IOException e) {
                    post(new Runnable() {
                        @Override public void run() {
                            indexing = false;
                            afterIndex.clear();
                            if (Session.current != null) message("No se pudo indexar el disco: " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    // ------------------------------------------------------------------ navegación

    private void setNav(int i) {
        navSelected = i;
        for (int k = 0; k < navItems.size(); k++) {
            navItems.get(k).setSelected(k == i);
            navItems.get(k).setTextColor(k == i ? Ui.ACCENT : Ui.TEXT);
        }
    }

    private void navigate(int i) {
        if (Session.current == null) { scan(); return; }
        endSelection();
        if (i == 5) { searchDialog(); return; }
        setNav(i);
        stack.clear();
        Screen sc = new Screen();
        switch (i) {
            case 0: sc.kind = FOLDER; sc.title = "Carpetas"; sc.grid = false; break;
            case 1: sc.kind = VIDEOS; sc.title = "Vídeos"; sc.groupBy = Search.GroupBy.FOLDER; break;
            case 2: sc.kind = PHOTOS; sc.title = "Fotos"; sc.groupBy = Search.GroupBy.MONTH; sc.sort = Search.Sort.DATE; sc.desc = true; break;
            case 3: sc.kind = MUSIC; sc.title = "Música"; sc.groupBy = Search.GroupBy.FOLDER; break;
            default: sc.kind = GROUPS; sc.title = "Artistas y grupos"; break;
        }
        stack.add(sc);
        load(sc);
    }

    private void push(Screen sc) {
        Screen cur = top();
        if (cur != null) cur.scroll = grid.getFirstVisiblePosition();
        stack.add(sc);
        load(sc);
    }

    private Screen top() { return stack.isEmpty() ? null : stack.get(stack.size() - 1); }

    /** Carga los datos de una pantalla (en segundo plano si hace falta) y la muestra. */
    private void load(final Screen sc) {
        final Session s = Session.current;
        if (s == null) return;
        if (sc.kind == FOLDER) {
            message("Cargando…");
            io.execute(new Runnable() {
                @Override public void run() {
                    try {
                        final List<Ntfs.Entry> l = s.fs.list(sc.folder);
                        post(new Runnable() { @Override public void run() { sc.base = l; if (top() == sc) render(); } });
                    } catch (final IOException e) {
                        post(new Runnable() { @Override public void run() { message("Error al leer la carpeta: " + e.getMessage()); } });
                    }
                }
            });
            render();
            return;
        }
        if (sc.kind == BUCKET || sc.kind == SEARCH) { render(); return; }
        message("Preparando el índice del disco…");
        ensureIndex(new Runnable() {
            @Override public void run() {
                MftIndex idx = s.index;
                List<Ntfs.Entry> l = new ArrayList<Ntfs.Entry>();
                if (sc.kind == VIDEOS || sc.kind == PHOTOS || sc.kind == MUSIC) {
                    MediaType t = sc.kind == VIDEOS ? MediaType.VIDEO : sc.kind == PHOTOS ? MediaType.IMAGE : MediaType.AUDIO;
                    for (Ntfs.Entry e : idx.all) if (!e.dir && (showHidden || !e.hidden) && MediaType.of(e.name) == t) l.add(e);
                } else if (sc.kind == GROUP || sc.kind == COVER) {
                    for (long r : sc.group.members) {
                        Ntfs.Entry e = idx.get(r);
                        if (e == null) continue;
                        if (sc.kind == COVER && !Thumbs.supports(e)) continue;
                        if (sc.filter != null && MediaType.of(e.name) != sc.filter) continue;
                        l.add(e);
                    }
                }
                sc.base = l;
                if (top() == sc) render();
            }
        }, true);
        if (sc.kind == GROUPS) render();
    }

    /** Recalcula las filas de la pantalla actual y dibuja título, barra de herramientas y mosaicos. */
    private void render() {
        final Screen sc = top();
        if (sc == null) return;
        Session s = Session.current;
        if (s == null) return;
        MftIndex idx = s.index;
        status.setVisibility(View.GONE);
        sc.rows = new ArrayList<Row>();

        if (sc.kind == GROUPS) {
            for (Groups.Group g : s.groups.sorted()) { Row r = new Row(); r.group = g; sc.rows.add(r); }
            sc.subtitle = s.groups.list.isEmpty() ? "Crea grupos para tus actores, cantantes o personas favoritas"
                    : s.groups.list.size() + " grupos";
        } else {
            List<Ntfs.Entry> base = new ArrayList<Ntfs.Entry>();
            for (Ntfs.Entry e : sc.base) if (showHidden || !e.hidden || sc.kind == SEARCH) base.add(e);
            Search.sort(base, idx, sc.sort, sc.desc);
            if (sc.groupBy != Search.GroupBy.NONE && idx != null) {
                for (Map.Entry<String, List<Ntfs.Entry>> m : Search.group(base, idx, sc.groupBy).entrySet()) {
                    Row r = new Row();
                    r.title = m.getKey();
                    r.items = m.getValue();
                    sc.rows.add(r);
                }
            } else {
                for (Ntfs.Entry e : base) { Row r = new Row(); r.e = e; sc.rows.add(r); }
            }
            if (sc.kind == FOLDER) sc.subtitle = folderPath() + "  ·  " + base.size() + " elementos";
            else if (sc.kind == GROUP) sc.subtitle = sc.group.category + "  ·  " + sc.group.members.size() + " archivos";
            else if (sc.kind == COVER) sc.subtitle = "Toca la imagen que quieres usar como portada";
            else sc.subtitle = base.size() + " archivos" + (sc.groupBy != Search.GroupBy.NONE ? "  ·  agrupados por " + sc.groupBy.label.toLowerCase() : "");
            if (base.isEmpty() && sc.kind != FOLDER) message(sc.kind == GROUP ? "Este grupo aún no tiene archivos. Mantén pulsado un archivo en cualquier pantalla y elige «Añadir a grupo»." : "No hay archivos.");
            if (base.isEmpty() && sc.kind == FOLDER && !sc.base.isEmpty()) message("Solo hay elementos ocultos.");
        }
        titleView.setText(sc.title);
        subtitleView.setText(sc.subtitle);
        buildToolbar(sc);
        adapter.set(sc.rows, sc.grid);
        grid.setNumColumns(sc.grid ? GridView.AUTO_FIT : 1);
        grid.setColumnWidth(dp(sc.kind == GROUPS ? 170 : 160));
        grid.setSelection(sc.scroll);
    }

    private String folderPath() {
        StringBuilder sb = new StringBuilder();
        for (Screen x : stack) if (x.kind == FOLDER) sb.append(sb.length() == 0 ? "Disco" : " / " + x.title);
        return sb.toString();
    }

    private void buildToolbar(final Screen sc) {
        toolbar.removeAllViews();
        if (stack.size() > 1) toolbar.addView(toolButton("←  Atrás", new View.OnClickListener() { @Override public void onClick(View v) { onBackPressed(); } }));
        if (sc.kind == GROUPS) {
            toolbar.addView(toolButton("＋  Nuevo grupo", new View.OnClickListener() { @Override public void onClick(View v) { newGroupDialog(null); } }));
            toolbar.addView(toolButton("✨  Sugerir grupos", new View.OnClickListener() { @Override public void onClick(View v) { suggest(); } }));
            return;
        }
        if (sc.kind == GROUP) {
            toolbar.addView(toolButton("▶  Reproducir", new View.OnClickListener() { @Override public void onClick(View v) { playAll(sc, false); } }));
            toolbar.addView(toolButton("🔀  Aleatorio", new View.OnClickListener() { @Override public void onClick(View v) { playAll(sc, true); } }));
            String[] f = {"Todo", "Vídeos", "Fotos", "Música"};
            final MediaType[] ft = {null, MediaType.VIDEO, MediaType.IMAGE, MediaType.AUDIO};
            for (int i = 0; i < f.length; i++) {
                final int k = i;
                TextView b = toolButton(f[i], new View.OnClickListener() { @Override public void onClick(View v) { sc.filter = ft[k]; load(sc); } });
                if (sc.filter == ft[i]) b.setBackground(Ui.round(Ui.ACCENT, dp(4)));
                toolbar.addView(b);
            }
            toolbar.addView(toolButton("🖼  Portada", new View.OnClickListener() { @Override public void onClick(View v) { pickCover(sc.group); } }));
            toolbar.addView(toolButton("✎  Editar", new View.OnClickListener() { @Override public void onClick(View v) { groupOptions(sc.group); } }));
        }
        if (sc.kind == MUSIC || sc.kind == VIDEOS || sc.kind == BUCKET || sc.kind == SEARCH || sc.kind == FOLDER) {
            if (hasPlayable(sc)) {
                toolbar.addView(toolButton("▶  Reproducir", new View.OnClickListener() { @Override public void onClick(View v) { playAll(sc, false); } }));
                toolbar.addView(toolButton("🔀  Aleatorio", new View.OnClickListener() { @Override public void onClick(View v) { playAll(sc, true); } }));
            }
        }
        if (sc.kind == COVER) return;
        toolbar.addView(toolButton("⇅  Ordenar", new View.OnClickListener() { @Override public void onClick(View v) { sortDialog(sc); } }));
        if (sc.kind != FOLDER && sc.kind != BUCKET) {
            toolbar.addView(toolButton("▦  Agrupar", new View.OnClickListener() { @Override public void onClick(View v) { groupByDialog(sc); } }));
        }
        toolbar.addView(toolButton(sc.grid ? "☰  Lista" : "▦  Mosaicos", new View.OnClickListener() {
            @Override public void onClick(View v) { sc.grid = !sc.grid; render(); }
        }));
        toolbar.addView(toolButton(showHidden ? "◉  Ocultos: sí" : "○  Ocultos: no", new View.OnClickListener() {
            @Override public void onClick(View v) { showHidden = !showHidden; if (sc.kind == FOLDER || sc.kind == BUCKET || sc.kind == SEARCH || sc.kind == GROUP) render(); else load(sc); }
        }));
        toolbar.addView(toolButton("☑  Seleccionar", new View.OnClickListener() { @Override public void onClick(View v) { startSelection(null); } }));
    }

    private boolean hasPlayable(Screen sc) {
        for (Ntfs.Entry e : sc.base) if (!e.dir && MediaType.of(e.name).playable()) return true;
        return false;
    }

    // ------------------------------------------------------------------ toques

    private void onTap(Row r) {
        Screen sc = top();
        if (sc == null) return;
        if (selecting && r.e != null) { toggleSelect(r.e); return; }
        if (r.group != null) { openGroup(r.group); return; }
        if (r.items != null) {
            Screen b = new Screen();
            b.kind = BUCKET;
            b.title = r.title;
            b.base = r.items;
            b.sort = sc.sort;
            b.desc = sc.desc;
            b.grid = sc.grid;
            push(b);
            return;
        }
        Ntfs.Entry e = r.e;
        if (sc.kind == COVER) {
            sc.group.cover = e.ref;
            saveGroups();
            stack.remove(stack.size() - 1);
            render();
            toast("Portada actualizada");
            return;
        }
        if (e.dir) {
            Screen f = new Screen();
            f.kind = FOLDER;
            f.folder = e.ref;
            f.title = e.name;
            f.grid = sc.grid;
            f.sort = sc.sort;
            f.desc = sc.desc;
            push(f);
            return;
        }
        openEntry(e, visibleEntries());
    }

    private List<Ntfs.Entry> visibleEntries() {
        List<Ntfs.Entry> l = new ArrayList<Ntfs.Entry>();
        Screen sc = top();
        if (sc != null) for (Row r : sc.rows) if (r.e != null) l.add(r.e);
        return l;
    }

    private void openEntry(Ntfs.Entry e, List<Ntfs.Entry> context) {
        MediaType t = MediaType.of(e.name);
        if (t == MediaType.IMAGE) {
            startViewer(ImageViewerActivity.class, e, context, MediaType.IMAGE);
        } else if (t == MediaType.VIDEO) {
            startViewer(VideoPlayerActivity.class, e, context, MediaType.VIDEO);
        } else if (t == MediaType.AUDIO) {
            List<Ntfs.Entry> l = filter(context, MediaType.AUDIO);
            Screen sc = top();
            AudioPlayer.get().play(this, l, Math.max(0, l.indexOf(e)), sc != null && sc.kind == GROUP ? sc.group.name : null);
        } else {
            toast("Vista previa no disponible para este tipo de archivo");
        }
    }

    private static List<Ntfs.Entry> filter(List<Ntfs.Entry> l, MediaType t) {
        List<Ntfs.Entry> o = new ArrayList<Ntfs.Entry>();
        for (Ntfs.Entry x : l) if (!x.dir && MediaType.of(x.name) == t) o.add(x);
        return o;
    }

    private void startViewer(Class<?> cls, Ntfs.Entry e, List<Ntfs.Entry> context, MediaType t) {
        List<Ntfs.Entry> l = filter(context, t);
        int i = l.indexOf(e);
        if (i < 0) { l = new ArrayList<Ntfs.Entry>(); l.add(e); i = 0; }
        Session.viewerList = l;
        Session.viewerStart = i;
        startActivity(new Intent(this, cls));
    }

    private void playAll(Screen sc, boolean shuffle) {
        List<Ntfs.Entry> all = new ArrayList<Ntfs.Entry>();
        for (Row r : sc.rows) {
            if (r.e != null) all.add(r.e);
            else if (r.items != null) all.addAll(r.items);
        }
        if (shuffle) Collections.shuffle(all);
        List<Ntfs.Entry> videos = filter(all, MediaType.VIDEO), audio = filter(all, MediaType.AUDIO);
        if (!audio.isEmpty() && (videos.isEmpty() || sc.kind == MUSIC)) {
            AudioPlayer.get().play(this, audio, 0, sc.kind == GROUP ? sc.group.name : null);
        } else if (!videos.isEmpty()) {
            Session.viewerList = videos;
            Session.viewerStart = 0;
            startActivity(new Intent(this, VideoPlayerActivity.class));
        } else {
            List<Ntfs.Entry> img = filter(all, MediaType.IMAGE);
            if (img.isEmpty()) { toast("No hay nada que reproducir"); return; }
            startViewer(ImageViewerActivity.class, img.get(0), img, MediaType.IMAGE);
        }
    }

    private void onLongTap(final Row r) {
        if (r.group != null) { groupOptions(r.group); return; }
        if (r.e != null && !r.e.dir) { startSelection(r.e); return; }
        if (r.e != null) toast("Las carpetas no se pueden añadir a un grupo; entra y selecciona sus archivos");
    }

    // ------------------------------------------------------------------ selección múltiple

    private void startSelection(Ntfs.Entry first) {
        selecting = true;
        selected.clear();
        if (first != null) selected.add(first.ref);
        updateSelBar();
        adapter.notifyDataSetChanged();
    }

    private void toggleSelect(Ntfs.Entry e) {
        if (e.dir) return;
        if (!selected.remove(e.ref)) selected.add(e.ref);
        updateSelBar();
        adapter.notifyDataSetChanged();
    }

    private void endSelection() {
        if (!selecting) return;
        selecting = false;
        selected.clear();
        selBar.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();
    }

    private void updateSelBar() {
        selBar.removeAllViews();
        selBar.setVisibility(View.VISIBLE);
        selCount = Ui.text(this, selected.size() + " seleccionados", 15, Ui.TEXT, Ui.MEDIUM);
        selBar.addView(selCount, new LinearLayout.LayoutParams(0, -2, 1f));
        final Screen sc = top();
        selBar.addView(selButton("Todo", new View.OnClickListener() {
            @Override public void onClick(View v) {
                for (Ntfs.Entry e : visibleEntries()) if (!e.dir) selected.add(e.ref);
                updateSelBar();
                adapter.notifyDataSetChanged();
            }
        }));
        selBar.addView(selButton("👤  Añadir a grupo", new View.OnClickListener() { @Override public void onClick(View v) { addToGroupDialog(); } }));
        if (sc != null && sc.kind == GROUP) {
            selBar.addView(selButton("Quitar del grupo", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    sc.group.members.removeAll(selected);
                    saveGroups();
                    endSelection();
                    load(sc);
                }
            }));
        }
        selBar.addView(selButton("✕", new View.OnClickListener() { @Override public void onClick(View v) { endSelection(); } }));
    }

    private TextView selButton(String s, View.OnClickListener l) {
        TextView t = Ui.button(this, s, Color.TRANSPARENT);
        t.setTextSize(14);
        t.setOnClickListener(l);
        return t;
    }

    // ------------------------------------------------------------------ grupos

    private void saveGroups() {
        Session s = Session.current;
        if (s == null) return;
        try {
            s.saveGroups(getApplicationContext());
        } catch (IOException e) {
            toast("No se pudieron guardar los grupos");
        }
    }

    private void openGroup(Groups.Group g) {
        Screen sc = new Screen();
        sc.kind = GROUP;
        sc.group = g;
        sc.title = g.name;
        sc.sort = Search.Sort.NAME;
        push(sc);
    }

    private void addToGroupDialog() {
        final Session s = Session.current;
        if (s == null || selected.isEmpty()) { toast("Selecciona primero algún archivo"); return; }
        final List<Groups.Group> gs = s.groups.sorted();
        String[] names = new String[gs.size() + 1];
        names[0] = "＋  Nuevo grupo…";
        for (int i = 0; i < gs.size(); i++) names[i + 1] = gs.get(i).name + "  (" + gs.get(i).category + ")";
        Ui.show(Ui.dialog(this).setTitle("Añadir " + selected.size() + " archivos a…")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (w == 0) {
                            newGroupDialog(new LinkedHashSet<Long>(selected));
                        } else {
                            Groups.Group g = gs.get(w - 1);
                            g.members.addAll(selected);
                            saveGroups();
                            toast("Añadidos al grupo");
                            endSelection();
                        }
                    }
                }));
    }

    /** Diálogo de nuevo grupo (o edición si {@code edit} no es null). */
    private void newGroupDialog(final Set<Long> members) {
        groupForm(null, members);
    }

    private void groupForm(final Groups.Group edit, final Set<Long> members) {
        final Session s = Session.current;
        if (s == null) return;
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText name = Ui.privateInput(this, "Nombre (p. ej. el actor o cantante)", false);
        if (edit != null) name.setText(edit.name);
        f.addView(name);
        final Spinner cat = new Spinner(this);
        cat.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, Groups.CATEGORIES));
        if (edit != null) for (int i = 0; i < Groups.CATEGORIES.length; i++) if (Groups.CATEGORIES[i].equals(edit.category)) cat.setSelection(i);
        f.addView(cat);
        Ui.show(Ui.dialog(this).setTitle(edit == null ? "Nuevo grupo" : "Editar grupo").setView(f)
                .setPositiveButton(edit == null ? "Crear" : "Guardar", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String n = name.getText().toString().trim();
                        if (n.isEmpty()) { toast("Escribe un nombre"); return; }
                        String c = Groups.CATEGORIES[cat.getSelectedItemPosition()];
                        Groups.Group g;
                        if (edit != null) { edit.name = n; edit.category = c; g = edit; }
                        else { g = s.groups.create(n, c); if (members != null) g.members.addAll(members); }
                        saveGroups();
                        endSelection();
                        Screen sc = top();
                        if (sc != null && sc.kind == GROUP && sc.group == g) sc.title = g.name;
                        render();
                        toast(edit == null ? "Grupo creado" : "Guardado");
                    }
                })
                .setNegativeButton("Cancelar", null));
    }

    private void groupOptions(final Groups.Group g) {
        Ui.show(Ui.dialog(this).setTitle(g.name)
                .setItems(new String[]{"Cambiar nombre o categoría", "Elegir portada", "Portada automática", "Eliminar grupo"},
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                if (w == 0) groupForm(g, null);
                                else if (w == 1) pickCover(g);
                                else if (w == 2) { g.cover = 0; saveGroups(); render(); }
                                else confirmDelete(g);
                            }
                        }));
    }

    private void confirmDelete(final Groups.Group g) {
        Ui.show(Ui.dialog(this).setTitle("¿Eliminar «" + g.name + "»?")
                .setMessage("Solo se borra el grupo. Los archivos del disco no se tocan.")
                .setPositiveButton("Eliminar", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Session s = Session.current;
                        if (s == null) return;
                        s.groups.remove(g);
                        saveGroups();
                        Screen sc = top();
                        if (sc != null && sc.kind == GROUP) stack.remove(stack.size() - 1);
                        render();
                    }
                })
                .setNegativeButton("Cancelar", null));
    }

    private void pickCover(final Groups.Group g) {
        Screen sc = new Screen();
        sc.kind = COVER;
        sc.group = g;
        sc.title = "Portada de " + g.name;
        push(sc);
    }

    private void suggest() {
        final Session s = Session.current;
        if (s == null) return;
        message("Analizando nombres de archivos y carpetas…");
        ensureIndex(new Runnable() {
            @Override public void run() {
                final MftIndex idx = s.index;
                io.execute(new Runnable() {
                    @Override public void run() {
                        List<Ntfs.Entry> media = new ArrayList<Ntfs.Entry>();
                        for (Ntfs.Entry e : idx.all) if (!e.dir && MediaType.of(e.name) != MediaType.OTHER && MediaType.of(e.name) != MediaType.DOCUMENT) media.add(e);
                        final List<Suggester.Suggestion> l = Suggester.suggest(idx, media);
                        post(new Runnable() { @Override public void run() { status.setVisibility(View.GONE); showSuggestions(l); } });
                    }
                });
            }
        }, true);
    }

    private void showSuggestions(final List<Suggester.Suggestion> l) {
        if (l.isEmpty()) { toast("No encontré nombres repetidos suficientes"); return; }
        String[] items = new String[l.size()];
        final boolean[] checked = new boolean[l.size()];
        for (int i = 0; i < l.size(); i++) {
            Suggester.Suggestion s = l.get(i);
            items[i] = s.name + "  ·  " + s.files.size() + " archivos  (" + s.reason + ")";
        }
        Ui.show(Ui.dialog(this).setTitle("Grupos sugeridos")
                .setMultiChoiceItems(items, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override public void onClick(DialogInterface d, int w, boolean on) { checked[w] = on; }
                })
                .setPositiveButton("Crear seleccionados", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        final List<Suggester.Suggestion> pick = new ArrayList<Suggester.Suggestion>();
                        for (int i = 0; i < checked.length; i++) if (checked[i]) pick.add(l.get(i));
                        if (pick.isEmpty()) return;
                        Ui.show(Ui.dialog(MainActivity.this).setTitle("¿Qué son?")
                                .setItems(Groups.CATEGORIES, new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d2, int c) {
                                        Session s = Session.current;
                                        if (s == null) return;
                                        for (Suggester.Suggestion sg : pick) {
                                            Groups.Group g = s.groups.create(sg.name, Groups.CATEGORIES[c]);
                                            for (Ntfs.Entry e : sg.files) g.members.add(e.ref);
                                        }
                                        saveGroups();
                                        render();
                                        toast(pick.size() + " grupos creados");
                                    }
                                }));
                    }
                })
                .setNegativeButton("Cancelar", null));
    }

    // ------------------------------------------------------------------ ordenar / agrupar / buscar

    private void sortDialog(final Screen sc) {
        final Search.Sort[] v = Search.Sort.values();
        String[] n = new String[v.length * 2];
        for (int i = 0; i < v.length; i++) {
            n[i * 2] = v[i].label + " ↑";
            n[i * 2 + 1] = v[i].label + " ↓";
        }
        int cur = sc.sort.ordinal() * 2 + (sc.desc ? 1 : 0);
        Ui.show(Ui.dialog(this).setTitle("Ordenar por").setSingleChoiceItems(n, cur, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                sc.sort = v[w / 2];
                sc.desc = w % 2 == 1;
                d.dismiss();
                render();
            }
        }));
    }

    private void groupByDialog(final Screen sc) {
        final Search.GroupBy[] v = Search.GroupBy.values();
        String[] n = new String[v.length];
        for (int i = 0; i < v.length; i++) n[i] = v[i].label;
        Ui.show(Ui.dialog(this).setTitle("Agrupar por").setSingleChoiceItems(n, sc.groupBy.ordinal(), new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                sc.groupBy = v[w];
                d.dismiss();
                ensureIndex(new Runnable() { @Override public void run() { render(); } }, true);
            }
        }));
    }

    private void searchDialog() {
        final Session s = Session.current;
        if (s == null) return;
        final Search q = lastSearch;
        ScrollView sv = new ScrollView(this);
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(dp(20), dp(8), dp(20), dp(8));
        sv.addView(f);
        final EditText text = Ui.privateInput(this, "Nombre contiene…", false);
        text.setText(q.text);
        f.addView(label("Nombre"));
        f.addView(text);
        final CheckBox inPath = new CheckBox(this);
        inPath.setText("Buscar también en los nombres de carpetas");
        inPath.setChecked(q.inPath);
        f.addView(inPath);
        f.addView(label("Tipo"));
        final String[] types = {"Todos", "Vídeos", "Fotos", "Música", "Documentos", "Otros", "Solo carpetas"};
        final Spinner type = spinner(types);
        type.setSelection(q.onlyFolders ? 6 : q.type == null ? 0 : q.type == MediaType.VIDEO ? 1 : q.type == MediaType.IMAGE ? 2
                : q.type == MediaType.AUDIO ? 3 : q.type == MediaType.DOCUMENT ? 4 : 5);
        f.addView(type);
        f.addView(label("Extensiones (separadas por comas)"));
        final EditText ext = Ui.privateInput(this, "p. ej. avi, mp4, mpg", false);
        ext.setText(join(q.exts));
        f.addView(ext);
        f.addView(label("Tamaño (MB)"));
        LinearLayout sizes = new LinearLayout(this);
        final EditText min = Ui.privateInput(this, "mínimo", true), max = Ui.privateInput(this, "máximo", true);
        if (q.minSize >= 0) min.setText(String.valueOf(q.minSize / (1024 * 1024)));
        if (q.maxSize >= 0) max.setText(String.valueOf(q.maxSize / (1024 * 1024)));
        sizes.addView(min, new LinearLayout.LayoutParams(0, -2, 1f));
        sizes.addView(max, new LinearLayout.LayoutParams(0, -2, 1f));
        f.addView(sizes);
        f.addView(label("Modificado"));
        final String[] dates = {"Cualquier fecha", "Últimas 24 horas", "Última semana", "Último mes", "Últimos 6 meses", "Último año", "Hace más de un año", "Hace más de 5 años"};
        final long[] spans = {0, 1, 7, 30, 182, 365, -365, -5 * 365};
        final Spinner date = spinner(dates);
        f.addView(date);
        f.addView(label("Ordenar por"));
        final Search.Sort[] sorts = Search.Sort.values();
        String[] sortNames = new String[sorts.length];
        for (int i = 0; i < sorts.length; i++) sortNames[i] = sorts[i].label;
        final Spinner sort = spinner(sortNames);
        sort.setSelection(q.sort.ordinal());
        f.addView(sort);
        final CheckBox desc = new CheckBox(this);
        desc.setText("Orden descendente (mayor / más reciente primero)");
        desc.setChecked(q.desc);
        f.addView(desc);
        f.addView(label("Agrupar resultados por"));
        final Search.GroupBy[] gbs = Search.GroupBy.values();
        String[] gbNames = new String[gbs.length];
        for (int i = 0; i < gbs.length; i++) gbNames[i] = gbs[i].label;
        final Spinner gb = spinner(gbNames);
        gb.setSelection(q.groupBy.ordinal());
        f.addView(gb);
        final CheckBox hidden = new CheckBox(this);
        hidden.setText("Incluir archivos ocultos y del sistema");
        hidden.setChecked(q.includeHidden);
        f.addView(hidden);

        Ui.show(Ui.dialog(this).setTitle("Buscar en el disco").setView(sv)
                .setPositiveButton("Buscar", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        final Search n = new Search();
                        n.text = text.getText().toString();
                        n.inPath = inPath.isChecked();
                        int t = type.getSelectedItemPosition();
                        n.onlyFolders = t == 6;
                        n.type = t == 1 ? MediaType.VIDEO : t == 2 ? MediaType.IMAGE : t == 3 ? MediaType.AUDIO
                                : t == 4 ? MediaType.DOCUMENT : t == 5 ? MediaType.OTHER : null;
                        n.setExtensions(ext.getText().toString());
                        n.minSize = mb(min);
                        n.maxSize = mb(max);
                        long span = spans[date.getSelectedItemPosition()];
                        long now = System.currentTimeMillis(), day = 24L * 3600 * 1000;
                        if (span > 0) n.from = now - span * day;
                        if (span < 0) n.to = now + span * day;
                        n.sort = sorts[sort.getSelectedItemPosition()];
                        n.desc = desc.isChecked();
                        n.groupBy = gbs[gb.getSelectedItemPosition()];
                        n.includeHidden = hidden.isChecked();
                        lastSearch = n;
                        runSearch(n);
                    }
                })
                .setNeutralButton("Limpiar", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { lastSearch = new Search(); searchDialog(); }
                })
                .setNegativeButton("Cancelar", null));
    }

    private void runSearch(final Search q) {
        message("Buscando…");
        ensureIndex(new Runnable() {
            @Override public void run() {
                Session s = Session.current;
                if (s == null) return;
                List<Ntfs.Entry> r = q.run(s.index);
                endSelection();
                if (top() == null || top().kind != SEARCH) setNav(5);
                Screen sc = new Screen();
                sc.kind = SEARCH;
                sc.title = q.text.trim().isEmpty() ? "Resultados" : "«" + q.text.trim() + "»";
                sc.base = r;
                sc.sort = q.sort;
                sc.desc = q.desc;
                sc.groupBy = q.groupBy;
                if (stack.isEmpty() || top().kind != SEARCH) { stack.clear(); }
                else stack.remove(stack.size() - 1);
                stack.add(sc);
                render();
                if (r.isEmpty()) message("Sin resultados.");
            }
        }, true);
    }

    private static long mb(EditText e) {
        try {
            String s = e.getText().toString().trim().replace(',', '.');
            return s.isEmpty() ? -1 : (long) (Double.parseDouble(s) * 1024 * 1024);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static String join(Set<String> s) {
        StringBuilder sb = new StringBuilder();
        for (String x : s) sb.append(sb.length() == 0 ? "" : ", ").append(x);
        return sb.toString();
    }

    private TextView label(String s) {
        TextView t = Ui.text(this, s, 13, Ui.ACCENT, Ui.MEDIUM);
        t.setPadding(0, dp(12), 0, 0);
        return t;
    }

    private Spinner spinner(String[] items) {
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, items));
        return sp;
    }

    // ------------------------------------------------------------------ barra de reproducción

    private final Runnable nowTick = new Runnable() {
        @Override public void run() {
            AudioPlayer p = AudioPlayer.get();
            if (p.active() && p.length() > 0) nowSeek.setProgress((int) (p.time() * 1000 / p.length()));
            if (p.active()) ui.postDelayed(this, 500);
        }
    };

    @Override
    public void onPlayerChanged() {
        AudioPlayer p = AudioPlayer.get();
        Ntfs.Entry e = p.current();
        if (e == null) { nowBar.setVisibility(View.GONE); return; }
        nowBar.setVisibility(View.VISIBLE);
        String n = e.name.replaceAll("\\.[^.]+$", "");
        nowTitle.setText(n);
        Session s = Session.current;
        String folder = s != null && s.index != null ? s.index.folderName(e) : "";
        nowSub.setText(p.group != null ? p.group : folder);
        nowPlay.setText(p.isPlaying() ? "⏸" : "▶");
        Bitmap cover = coverFor(e);
        if (cover != null) nowCover.setImageBitmap(cover); else nowCover.setImageDrawable(null);
        ui.removeCallbacks(nowTick);
        ui.post(nowTick);
    }

    /** Portada para una canción: imagen dentro de su carpeta (cover/folder.jpg) si ya está en caché. */
    private android.graphics.Bitmap coverFor(Ntfs.Entry e) {
        Session s = Session.current;
        if (s == null || s.index == null) return null;
        Ntfs.Entry best = null;
        for (Ntfs.Entry x : s.index.all) {
            if (x.parent != e.parent || x.dir || MediaType.of(x.name) != MediaType.IMAGE) continue;
            String n = x.name.toLowerCase();
            if (best == null || n.startsWith("cover") || n.startsWith("folder") || n.startsWith("front")) best = x;
        }
        if (best == null) return null;
        Thumbs.get().load(this, best, nowCover);
        return Thumbs.get().cached(best.ref);
    }

    // ------------------------------------------------------------------ mosaicos

    private final class TileAdapter extends BaseAdapter {
        private List<Row> rows = new ArrayList<Row>();
        private boolean tiles = true;
        private final DateFormat df = DateFormat.getDateInstance(DateFormat.MEDIUM);

        void set(List<Row> r, boolean asTiles) { rows = r; tiles = asTiles; notifyDataSetChanged(); }

        @Override public int getCount() { return rows.size(); }
        @Override public Row getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int i) { return tiles ? 0 : 1; }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            Holder h = v == null ? null : (Holder) v.getTag();
            if (h == null) {
                h = tiles ? tileView() : rowView();
                v = h.root;
                v.setTag(h);
            }
            bind(h, rows.get(pos));
            return v;
        }

        private Holder tileView() {
            Holder h = new Holder();
            LinearLayout l = new LinearLayout(MainActivity.this);
            l.setOrientation(LinearLayout.VERTICAL);
            h.frame = new SquareFrame(MainActivity.this);
            h.placeholder = Ui.text(MainActivity.this, "", 34, Ui.TEXT, Ui.LIGHT);
            h.placeholder.setGravity(Gravity.CENTER);
            h.frame.addView(h.placeholder, new FrameLayout.LayoutParams(-1, -1));
            h.image = new ImageView(MainActivity.this);
            h.image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            h.frame.addView(h.image, new FrameLayout.LayoutParams(-1, -1));
            h.check = Ui.text(MainActivity.this, "", 20, Ui.TEXT, null);
            h.check.setPadding(dp(6), dp(2), dp(6), dp(2));
            h.frame.addView(h.check, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT));
            l.addView(h.frame, new LinearLayout.LayoutParams(-1, -2));
            h.name = Ui.text(MainActivity.this, "", 14, Ui.TEXT, null);
            h.name.setMaxLines(2);
            h.name.setPadding(0, dp(6), 0, 0);
            h.sub = Ui.text(MainActivity.this, "", 12, Ui.SUB, null);
            h.sub.setSingleLine(true);
            l.addView(h.name);
            l.addView(h.sub);
            h.root = l;
            return h;
        }

        private Holder rowView() {
            Holder h = new Holder();
            LinearLayout l = new LinearLayout(MainActivity.this);
            l.setGravity(Gravity.CENTER_VERTICAL);
            l.setPadding(0, dp(2), 0, dp(2));
            h.frame = new FrameLayout(MainActivity.this);
            h.placeholder = Ui.text(MainActivity.this, "", 20, Ui.TEXT, null);
            h.placeholder.setGravity(Gravity.CENTER);
            h.frame.addView(h.placeholder, new FrameLayout.LayoutParams(-1, -1));
            h.image = new ImageView(MainActivity.this);
            h.image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            h.frame.addView(h.image, new FrameLayout.LayoutParams(-1, -1));
            l.addView(h.frame, new LinearLayout.LayoutParams(dp(48), dp(48)));
            LinearLayout t = new LinearLayout(MainActivity.this);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setPadding(dp(14), 0, dp(8), 0);
            h.name = Ui.text(MainActivity.this, "", 16, Ui.TEXT, null);
            h.name.setSingleLine(true);
            h.sub = Ui.text(MainActivity.this, "", 12, Ui.SUB, null);
            h.sub.setSingleLine(true);
            t.addView(h.name);
            t.addView(h.sub);
            l.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            h.check = Ui.text(MainActivity.this, "", 20, Ui.TEXT, null);
            l.addView(h.check);
            h.root = l;
            return h;
        }

        private void bind(Holder h, Row r) {
            Session s = Session.current;
            MftIndex idx = s == null ? null : s.index;
            h.image.setImageDrawable(null);
            h.image.setTag(null);
            h.check.setText("");
            h.root.setAlpha(1f);
            boolean circle = false;
            Ntfs.Entry thumb = null;
            int color = Ui.CARD;
            String ph;
            if (r.group != null) {
                Groups.Group g = r.group;
                h.name.setText(g.name);
                h.sub.setText(g.category + " · " + g.members.size());
                circle = true;
                color = Ui.tileColor(g.name);
                ph = Ui.initials(g.name);
                thumb = coverOf(g, idx);
            } else if (r.items != null) {
                h.name.setText(r.title);
                h.sub.setText(r.items.size() + " archivos");
                ph = "🗂";
                for (Ntfs.Entry x : r.items) if (Thumbs.supports(x)) { thumb = x; break; }
                color = Ui.tileColor(r.title);
            } else {
                Ntfs.Entry e = r.e;
                h.name.setText(e.name);
                MediaType t = MediaType.of(e.name);
                String date = e.mtime > 0 ? df.format(new Date(e.mtime)) : "";
                h.sub.setText(e.dir ? date : Util.humanSize(e.size) + (date.isEmpty() ? "" : " · " + date));
                ph = e.dir ? "📁" : t == MediaType.VIDEO ? "🎬" : t == MediaType.IMAGE ? "🖼" : t == MediaType.AUDIO ? "🎵" : t == MediaType.DOCUMENT ? "📄" : "📦";
                if (Thumbs.supports(e)) thumb = e;
                if (e.hidden) h.root.setAlpha(0.55f);
                if (selecting && !e.dir) h.check.setText(selected.contains(e.ref) ? "☑" : "☐");
                color = e.dir ? 0xFF3A3A3A : Ui.CARD;
            }
            h.placeholder.setText(ph);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(color);
            if (circle) bg.setShape(GradientDrawable.OVAL); else bg.setCornerRadius(dp(3));
            h.frame.setBackground(bg);
            final boolean round = circle;
            h.frame.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline o) {
                    if (round) o.setOval(0, 0, view.getWidth(), view.getHeight());
                    else o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(3));
                }
            });
            h.frame.setClipToOutline(true);
            if (thumb != null) Thumbs.get().load(MainActivity.this, thumb, h.image);
        }
    }

    private Ntfs.Entry coverOf(Groups.Group g, MftIndex idx) {
        if (idx == null) return null;
        if (g.cover != 0) {
            Ntfs.Entry c = idx.get(g.cover);
            if (c != null) return c;
        }
        Ntfs.Entry video = null;
        for (long r : g.members) {
            Ntfs.Entry e = idx.get(r);
            if (e == null || !Thumbs.supports(e)) continue;
            if (MediaType.of(e.name) == MediaType.IMAGE) return e;
            if (video == null) video = e;
        }
        return video;
    }

    private static final class Holder {
        View root;
        FrameLayout frame;
        ImageView image;
        TextView placeholder, name, sub, check;
    }

    /** Marco cuadrado (alto = ancho). */
    private static final class SquareFrame extends FrameLayout {
        SquareFrame(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) { super.onMeasure(w, w); }
    }
}

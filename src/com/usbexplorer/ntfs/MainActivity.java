package com.usbexplorer.ntfs;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.usbexplorer.ntfs.core.Disk;
import com.usbexplorer.ntfs.core.Ntfs;
import com.usbexplorer.ntfs.core.Partitions;
import com.usbexplorer.ntfs.core.Util;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String ACTION_PERMISSION = "com.usbexplorer.ntfs.USB_PERMISSION";
    private static final int REQ_TREE = 1;
    private static final String PREFS = "ajustes", KEY_DEST = "destino";

    private static final String POWER_HINT =
            "Si el disco gira pero da error o no aparece, lo más probable es que le falte energía: "
            + "usa un hub USB-C con carga (PD) conectado al cargador.";
    private static final String NO_DEVICE =
            "No se detecta ningún dispositivo USB.\n\n"
            + "• Comprueba que el adaptador OTG esté bien conectado.\n"
            + "• Si el disco gira pero no aparece aquí, casi seguro le falta energía: "
            + "usa un hub USB-C con carga (PD).\n"
            + "• Prueba con un pendrive para descartar el adaptador.\n\n"
            + "Cuando lo conectes, pulsa «Buscar disco».";

    private UsbManager usb;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private TextView status, path;
    private CheckBox hiddenBox;
    private EntryAdapter adapter;
    private final ArrayList<Long> refs = new ArrayList<Long>();
    private final ArrayList<String> names = new ArrayList<String>();
    private List<Ntfs.Entry> current = Collections.emptyList();
    private boolean opening;
    private Ntfs.Entry pendingCopy;
    private volatile boolean cancelCopy;
    private String diskInfo = "";

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            String a = i.getAction();
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                Session s = Session.current;
                if (s != null && (d == null || d.getDeviceName().equals(s.usb.device.getDeviceName()))) {
                    Session.close();
                    refs.clear(); names.clear();
                    current = Collections.emptyList();
                    adapter.setItems(current);
                    path.setText("");
                    showMessage("Disco desconectado.");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                scan();
            } else if (ACTION_PERMISSION.equals(a)) {
                if (d != null && usb.hasPermission(d)) openDevice(d);
                else showMessage("Sin permiso no puedo leer el disco.\n\nPulsa «Buscar disco» para intentarlo otra vez.");
            }
        }
    };

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        usb = getSystemService(UsbManager.class);
        buildUi();
        IntentFilter f = new IntentFilter(ACTION_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(receiver, f);
        if (Session.current != null) {
            Session s = Session.current;
            refs.add(Ntfs.ROOT);
            names.add(s.fs.label().isEmpty() ? "Disco" : s.fs.label());
            loadFolder();
        } else {
            scan();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (Session.current == null) scan();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(receiver);
        if (isFinishing()) Session.close();
        io.shutdown();
    }

    @Override
    public void onBackPressed() {
        if (refs.size() > 1) {
            refs.remove(refs.size() - 1);
            names.remove(names.size() - 1);
            loadFolder();
        } else {
            super.onBackPressed();
        }
    }

    // ------------------------------------------------------------------ interfaz

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), 0);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        Button rescan = new Button(this);
        rescan.setText("Buscar disco");
        rescan.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (Session.current != null && !refs.isEmpty()) loadFolder(); else scan();
            }
        });
        Button dest = new Button(this);
        dest.setText("Carpeta destino");
        dest.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickDestination(); }
        });
        hiddenBox = new CheckBox(this);
        hiddenBox.setText("Mostrar ocultos");
        hiddenBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) { refreshList(); }
        });
        bar.addView(rescan);
        bar.addView(dest);
        bar.addView(hiddenBox);
        root.addView(bar);

        path = new TextView(this);
        path.setTypeface(Typeface.DEFAULT_BOLD);
        path.setTextSize(17);
        path.setPadding(0, dp(6), 0, dp(4));
        root.addView(path);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, dp(4), 0, dp(8));
        root.addView(status);

        ListView list = new ListView(this);
        adapter = new EntryAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Ntfs.Entry e = adapter.getItem(pos);
                if (e.dir) {
                    refs.add(e.ref);
                    names.add(e.name);
                    loadFolder();
                } else {
                    openFile(e, false);
                }
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                showActions(adapter.getItem(pos));
                return true;
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private void showMessage(String msg) {
        status.setVisibility(View.VISIBLE);
        status.setText(msg);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void post(Runnable r) {
        ui.post(r);
    }

    // ------------------------------------------------------------------ conexión del disco

    private void scan() {
        if (opening || Session.current != null) return;
        UsbDevice target = null;
        StringBuilder sb = new StringBuilder();
        for (UsbDevice d : usb.getDeviceList().values()) {
            sb.append("• ").append(describe(d)).append('\n');
            if (target == null && UsbMassStorage.findInterface(d) != null) target = d;
        }
        if (target == null) {
            if (sb.length() == 0) showMessage(NO_DEVICE);
            else showMessage("Hay un dispositivo USB conectado, pero no es un disco de almacenamiento compatible:\n\n"
                    + sb + "\n" + POWER_HINT);
            return;
        }
        if (!usb.hasPermission(target)) {
            showMessage("Disco detectado: " + describe(target)
                    + "\n\nAcepta el permiso que aparece en pantalla para que la app pueda leerlo.");
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
        return String.format("%04X:%04X %s (clase %s)", d.getVendorId(), d.getProductId(),
                name == null || name.isEmpty() ? "" : name, cls);
    }

    private void openDevice(final UsbDevice d) {
        if (opening || Session.current != null) return;
        opening = true;
        showMessage("Conectando con el disco…\nUn disco mecánico puede tardar unos segundos en arrancar.");
        io.execute(new Runnable() {
            @Override public void run() {
                UsbMassStorage ms = null;
                try {
                    ms = UsbMassStorage.open(usb, d);
                    final Disk disk = new Disk(ms);
                    final List<Partitions.Part> parts = Partitions.scan(disk);
                    final String info = (ms.vendor + " " + ms.product).trim() + " · " + Util.humanSize(disk.size());
                    final List<Partitions.Part> ntfs = new ArrayList<Partitions.Part>();
                    boolean native_ = false;
                    for (Partitions.Part p : parts) {
                        if (p.isNtfs()) ntfs.add(p);
                        else if (!"?".equals(p.fs)) native_ = true;
                    }
                    if (ntfs.isEmpty()) {
                        ms.close();
                        StringBuilder sb = new StringBuilder("El disco (" + info + ") no tiene particiones NTFS.\n\n");
                        for (Partitions.Part p : parts) sb.append("• ").append(p).append('\n');
                        if (parts.isEmpty()) sb.append("No se encontró ninguna tabla de particiones.\n");
                        if (native_) sb.append("\nEse formato Android ya lo lee directamente: ábrelo con «Mis archivos».");
                        final String msg = sb.toString();
                        post(new Runnable() { @Override public void run() { opening = false; showMessage(msg); } });
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
                            showMessage("No se pudo leer el disco:\n" + e.getMessage() + "\n\n" + POWER_HINT);
                        }
                    });
                }
            }
        });
    }

    private void choosePartition(final UsbMassStorage ms, final Disk disk,
                                 final List<Partitions.Part> parts, final String info) {
        String[] items = new String[parts.size()];
        for (int i = 0; i < items.length; i++) items[i] = parts.get(i).toString();
        new AlertDialog.Builder(this)
                .setTitle("Elige la partición")
                .setCancelable(false)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dlg, int which) {
                        mount(ms, disk, parts.get(which), info);
                    }
                })
                .show();
    }

    private void mount(final UsbMassStorage ms, final Disk disk, final Partitions.Part part, final String info) {
        showMessage("Leyendo NTFS…");
        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    final Ntfs fs = new Ntfs(disk, part.offset);
                    Session.current = new Session(ms, disk, fs);
                    post(new Runnable() {
                        @Override public void run() {
                            opening = false;
                            diskInfo = info;
                            refs.clear(); names.clear();
                            refs.add(Ntfs.ROOT);
                            names.add(fs.label().isEmpty() ? "Disco" : fs.label());
                            loadFolder();
                        }
                    });
                } catch (final IOException e) {
                    ms.close();
                    post(new Runnable() {
                        @Override public void run() {
                            opening = false;
                            showMessage("No se pudo leer la partición NTFS:\n" + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    // ------------------------------------------------------------------ navegación

    private void loadFolder() {
        final Session s = Session.current;
        if (s == null || refs.isEmpty()) { scan(); return; }
        final long ref = refs.get(refs.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) sb.append(i == 0 ? "" : " / ").append(names.get(i));
        path.setText(sb);
        showMessage("Cargando…");
        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    final List<Ntfs.Entry> l = s.fs.list(ref);
                    post(new Runnable() {
                        @Override public void run() {
                            if (refs.isEmpty() || refs.get(refs.size() - 1) != ref) return;
                            current = l;
                            refreshList();
                        }
                    });
                } catch (final IOException e) {
                    post(new Runnable() {
                        @Override public void run() { showMessage("Error al leer la carpeta:\n" + e.getMessage()); }
                    });
                }
            }
        });
    }

    private void refreshList() {
        List<Ntfs.Entry> shown = new ArrayList<Ntfs.Entry>();
        boolean all = hiddenBox.isChecked();
        for (Ntfs.Entry e : current) if (all || !e.hidden) shown.add(e);
        adapter.setItems(shown);
        if (Session.current == null) return;
        String count = shown.isEmpty() ? "Carpeta vacía" : shown.size() + " elementos";
        int hidden = current.size() - shown.size();
        if (hidden > 0) count += " (" + hidden + " ocultos)";
        showMessage(diskInfo.isEmpty() ? count : diskInfo + "\n" + count);
    }

    private void showActions(final Ntfs.Entry e) {
        final String[] items = e.dir
                ? new String[]{"Copiar carpeta a la tablet"}
                : new String[]{"Copiar a la tablet", "Abrir con…"};
        new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == 0) startCopy(e);
                        else openFile(e, true);
                    }
                })
                .show();
    }

    private void openFile(Ntfs.Entry e, boolean chooser) {
        String mime = FilesProvider.mimeFor(e.name);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(FilesProvider.uriFor(e), mime);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            if (chooser || mime.equals("application/octet-stream")) {
                startActivity(Intent.createChooser(i, "Abrir " + e.name + " con"));
            } else {
                startActivity(i);
            }
        } catch (ActivityNotFoundException ex) {
            toast("No hay ninguna app para abrir este tipo de archivo. Puedes copiarlo a la tablet (mantén pulsado).");
        }
    }

    // ------------------------------------------------------------------ copiar a la tablet

    private void pickDestination() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE);
            toast("Elige la carpeta de la tablet donde guardar las copias y pulsa «Usar esta carpeta».");
        } catch (ActivityNotFoundException e) {
            toast("No se puede abrir el selector de carpetas");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_TREE) return;
        Ntfs.Entry p = pendingCopy;
        pendingCopy = null;
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_DEST, tree.toString()).apply();
        toast("Carpeta destino guardada");
        if (p != null) runCopy(p, tree);
    }

    private Uri destination() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String s = sp.getString(KEY_DEST, null);
        if (s == null) return null;
        Uri u = Uri.parse(s);
        for (UriPermission p : getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(u) && p.isWritePermission()) return u;
        }
        return null;
    }

    private void startCopy(Ntfs.Entry e) {
        Uri tree = destination();
        if (tree == null) {
            pendingCopy = e;
            pickDestination();
        } else {
            runCopy(e, tree);
        }
    }

    private void runCopy(final Ntfs.Entry e, final Uri tree) {
        final Session s = Session.current;
        if (s == null) { toast("El disco no está conectado"); return; }
        cancelCopy = false;
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle("Copiando «" + e.name + "»");
        pd.setMessage("Calculando tamaño…");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(1000);
        pd.setProgressNumberFormat(null);
        pd.setCancelable(false);
        pd.setButton(DialogInterface.BUTTON_NEGATIVE, "Cancelar", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) { cancelCopy = true; }
        });
        pd.show();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        io.execute(new Runnable() {
            @Override public void run() {
                String result;
                boolean ok = false;
                try {
                    long total = e.dir ? totalSize(s.fs, e.ref) : s.fs.open(e.ref).size();
                    Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
                    Copier c = new Copier(s.fs, getContentResolver(), pd, total);
                    c.copy(e, parent);
                    result = "Copiado: " + e.name + " (" + Util.humanSize(c.copied) + ", " + c.files + " archivos)";
                    ok = true;
                } catch (InterruptedIOException ex) {
                    result = "Copia cancelada";
                } catch (Exception ex) {
                    result = "Error al copiar:\n" + ex.getMessage();
                }
                final String msg = result;
                final boolean success = ok;
                post(new Runnable() {
                    @Override public void run() {
                        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        try { pd.dismiss(); } catch (Exception ignored) { }
                        if (success) toast(msg);
                        else new AlertDialog.Builder(MainActivity.this).setMessage(msg)
                                .setPositiveButton("Aceptar", null).show();
                    }
                });
            }
        });
    }

    private static boolean skipOnCopy(Ntfs.Entry e) {
        return (e.hidden && e.name.startsWith("$")) || e.name.equals("System Volume Information");
    }

    private long totalSize(Ntfs fs, long ref) throws IOException {
        long t = 0;
        for (Ntfs.Entry e : fs.list(ref)) {
            if (cancelCopy) throw new InterruptedIOException();
            if (skipOnCopy(e)) continue;
            t += e.dir ? totalSize(fs, e.ref) : e.size;
        }
        return t;
    }

    private final class Copier {
        final Ntfs fs;
        final ContentResolver cr;
        final ProgressDialog pd;
        final long total;
        final long start = SystemClock.elapsedRealtime();
        final byte[] buf = new byte[1 << 20];
        long copied, lastUi;
        int files;

        Copier(Ntfs fs, ContentResolver cr, ProgressDialog pd, long total) {
            this.fs = fs; this.cr = cr; this.pd = pd; this.total = Math.max(1, total);
        }

        void copy(Ntfs.Entry e, Uri parent) throws IOException {
            if (cancelCopy) throw new InterruptedIOException();
            if (e.dir) {
                Uri d = DocumentsContract.createDocument(cr, parent, DocumentsContract.Document.MIME_TYPE_DIR, e.name);
                if (d == null) throw new IOException("No se pudo crear la carpeta " + e.name);
                for (Ntfs.Entry c : fs.list(e.ref)) if (!skipOnCopy(c)) copy(c, d);
                return;
            }
            Ntfs.FileHandle h = fs.open(e.ref);
            Uri f = DocumentsContract.createDocument(cr, parent, "application/octet-stream", e.name);
            if (f == null) throw new IOException("No se pudo crear el archivo " + e.name);
            OutputStream os = cr.openOutputStream(f);
            if (os == null) throw new IOException("No se pudo escribir " + e.name);
            boolean done = false;
            try {
                long pos = 0;
                while (pos < h.size()) {
                    if (cancelCopy) throw new InterruptedIOException();
                    int n = fs.read(h, pos, buf, 0, buf.length);
                    if (n <= 0) throw new IOException("Lectura incompleta de " + e.name);
                    os.write(buf, 0, n);
                    pos += n;
                    copied += n;
                    progress(e.name);
                }
                done = true;
            } finally {
                try { os.close(); } catch (IOException ignored) { }
                if (!done) {
                    try { DocumentsContract.deleteDocument(cr, f); } catch (Exception ignored) { }
                }
            }
            files++;
        }

        void progress(final String name) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastUi < 300) return;
            lastUi = now;
            final int p = (int) Math.min(1000, copied * 1000 / total);
            double secs = Math.max(0.001, (now - start) / 1000.0);
            final String msg = name + "\n" + Util.humanSize(copied) + " de " + Util.humanSize(total)
                    + " · " + Util.humanSize((long) (copied / secs)) + "/s";
            post(new Runnable() {
                @Override public void run() { pd.setProgress(p); pd.setMessage(msg); }
            });
        }
    }

    // ------------------------------------------------------------------ lista

    private final class EntryAdapter extends BaseAdapter {
        private List<Ntfs.Entry> items = Collections.emptyList();
        private final DateFormat df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);

        void setItems(List<Ntfs.Entry> l) { items = l; notifyDataSetChanged(); }

        @Override public int getCount() { return items.size(); }
        @Override public Ntfs.Entry getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            Holder h;
            if (v == null) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(8), 0, dp(8));
                h = new Holder();
                h.icon = new TextView(MainActivity.this);
                h.icon.setTextSize(26);
                h.icon.setPadding(0, 0, dp(12), 0);
                LinearLayout texts = new LinearLayout(MainActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                h.name = new TextView(MainActivity.this);
                h.name.setTextSize(17);
                h.detail = new TextView(MainActivity.this);
                h.detail.setTextSize(13);
                h.detail.setTextColor(Color.GRAY);
                texts.addView(h.name);
                texts.addView(h.detail);
                row.addView(h.icon);
                row.addView(texts);
                row.setTag(h);
                v = row;
            } else {
                h = (Holder) v.getTag();
            }
            Ntfs.Entry e = items.get(pos);
            h.icon.setText(e.dir ? "📁" : icon(e.name));
            h.name.setText(e.name);
            h.name.setAlpha(e.hidden ? 0.5f : 1f);
            String date = e.mtime > 0 ? df.format(new Date(e.mtime)) : "";
            h.detail.setText(e.dir ? date : Util.humanSize(e.size) + "   " + date);
            return v;
        }
    }

    private static final class Holder {
        TextView icon, name, detail;
    }

    private static String icon(String name) {
        String m = FilesProvider.mimeFor(name);
        if (m.startsWith("image/")) return "🖼";
        if (m.startsWith("video/")) return "🎬";
        if (m.startsWith("audio/")) return "🎵";
        if (m.equals("application/pdf")) return "📕";
        return "📄";
    }
}

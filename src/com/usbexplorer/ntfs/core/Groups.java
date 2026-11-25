package com.usbexplorer.ntfs.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Grupos virtuales (actores, cantantes, ...) de un disco. NO modifican el disco: solo guardan
 * referencias MFT, que se mantienen aunque el archivo se renombre o mueva en Windows.
 * Se guardan cifrados en la tablet (ver SecureStore en la app).
 */
public final class Groups {
    public static final String[] CATEGORIES = {"Actores", "Cantantes / Grupos", "Personas", "Películas / Series", "Otros"};

    public static final class Group {
        public long id;
        public String name;
        public String category;
        /** Imagen del disco elegida como portada (referencia MFT), 0 = automática. */
        public long cover;
        public final Set<Long> members = new LinkedHashSet<Long>();
    }

    public final List<Group> list = new ArrayList<Group>();
    private long nextId = 1;

    public Group create(String name, String category) {
        Group g = find(name);
        if (g != null) return g;
        g = new Group();
        g.id = nextId++;
        g.name = name.trim();
        g.category = category;
        list.add(g);
        return g;
    }

    public Group find(String name) {
        String n = MftIndex.normalize(name.trim());
        for (Group g : list) if (MftIndex.normalize(g.name).equals(n)) return g;
        return null;
    }

    public Group byId(long id) {
        for (Group g : list) if (g.id == id) return g;
        return null;
    }

    public void remove(Group g) { list.remove(g); }

    public List<Group> sorted() {
        List<Group> l = new ArrayList<Group>(list);
        Collections.sort(l, new Comparator<Group>() {
            @Override public int compare(Group a, Group b) {
                int c = a.category.compareTo(b.category);
                return c != 0 ? c : a.name.compareToIgnoreCase(b.name);
            }
        });
        return l;
    }

    public byte[] serialize() throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bo);
        o.writeInt(0x47525032);   // "GRP2"
        o.writeLong(nextId);
        o.writeInt(list.size());
        for (Group g : list) {
            o.writeLong(g.id);
            o.writeUTF(g.name);
            o.writeUTF(g.category);
            o.writeLong(g.cover);
            o.writeInt(g.members.size());
            for (long r : g.members) o.writeLong(r);
        }
        o.flush();
        return bo.toByteArray();
    }

    public static Groups parse(byte[] b) throws IOException {
        Groups gs = new Groups();
        if (b == null || b.length == 0) return gs;
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
        int magic = in.readInt();
        if (magic != 0x47525031 && magic != 0x47525032) throw new IOException("Formato de grupos desconocido");
        gs.nextId = in.readLong();
        int n = in.readInt();
        for (int i = 0; i < n; i++) {
            Group g = new Group();
            g.id = in.readLong();
            g.name = in.readUTF();
            g.category = in.readUTF();
            if (magic == 0x47525032) g.cover = in.readLong();
            int m = in.readInt();
            for (int k = 0; k < m; k++) g.members.add(in.readLong());
            gs.list.add(g);
        }
        return gs;
    }
}

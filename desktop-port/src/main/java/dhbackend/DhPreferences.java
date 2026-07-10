package dhbackend;

import com.badlogic.gdx.Preferences;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Backend Preferences desktop ({@code com.badlogic.gdx.Preferences} — 19 méthodes), adossé à
 * un fichier .prefs XML sur disque. Porté de DragonSoul `DsPreferences` (noms dé-obfusqués).
 * Fidélité : RÉEL.
 */
public final class DhPreferences implements Preferences {
    private final Properties props = new Properties();
    private final File file;

    public DhPreferences(File dir, String name) {
        dir.mkdirs();
        this.file = new File(dir, name + ".prefs");
        if (file.exists()) {
            try (InputStream in = new FileInputStream(file)) { props.loadFromXML(in); }
            catch (Exception ignored) { }
        }
    }

    @Override public Preferences putBoolean(String k, boolean v) { props.setProperty(k, Boolean.toString(v)); return this; }
    @Override public Preferences putInteger(String k, int v) { props.setProperty(k, Integer.toString(v)); return this; }
    @Override public Preferences putLong(String k, long v) { props.setProperty(k, Long.toString(v)); return this; }
    @Override public Preferences putFloat(String k, float v) { props.setProperty(k, Float.toString(v)); return this; }
    @Override public Preferences putString(String k, String v) { props.setProperty(k, v); return this; }

    @Override public boolean getBoolean(String k) { return getBoolean(k, false); }
    @Override public boolean getBoolean(String k, boolean def) { String v = props.getProperty(k); return v != null ? Boolean.parseBoolean(v) : def; }
    @Override public int getInteger(String k) { return getInteger(k, 0); }
    @Override public int getInteger(String k, int def) { try { return Integer.parseInt(props.getProperty(k)); } catch (Exception e) { return def; } }
    @Override public long getLong(String k) { return getLong(k, 0L); }
    @Override public long getLong(String k, long def) { try { return Long.parseLong(props.getProperty(k)); } catch (Exception e) { return def; } }
    @Override public float getFloat(String k, float def) { try { return Float.parseFloat(props.getProperty(k)); } catch (Exception e) { return def; } }
    @Override public String getString(String k) { return getString(k, ""); }
    @Override public String getString(String k, String def) { String v = props.getProperty(k); return v != null ? v : def; }

    @Override public boolean contains(String k) { return props.containsKey(k); }
    @Override public void remove(String k) { props.remove(k); }
    @Override public void clear() { props.clear(); }

    @Override public Map<String, ?> get() {
        Map<String, Object> m = new HashMap<>();
        for (String n : props.stringPropertyNames()) m.put(n, props.getProperty(n));
        return m;
    }

    @Override public void flush() {
        try (OutputStream out = new FileOutputStream(file)) { props.storeToXML(out, null); }
        catch (Exception ignored) { }
    }
}

package com.badlogic.gdx.utils;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * SHADOW de {@code com.badlogic.gdx.utils.DataInput} de libGDX.
 *
 * <p>Le libGDX de PerBlue (dans game.jar) a été réduit par ProGuard : sa classe {@code DataInput}
 * ne conserve QUE le constructeur (le jeu n'utilise que les méthodes héritées de
 * {@link DataInputStream}). Mais {@code spine-libgdx 3.6.53.1} (notre runtime Spine, cf. module
 * cspine) appelle {@code DataInput.readString()} et {@code readInt(boolean)} (var-int) lors du
 * décodage des {@code .skel} → {@code NoSuchMethodError} (visible dès les squelettes du décor du
 * MainScreen, et en réalité sur TOUT chargement {@code .skel}).
 *
 * <p>On restaure ces méthodes en fournissant l'implémentation EXACTE de libGDX 1.9.7 (la version du
 * core PerBlue), placée avant game.jar sur le classpath. Les méthodes héritées ({@code readInt()},
 * {@code readFloat()}, …) restent identiques → le code du jeu est inchangé. Fidélité : RÉEL
 * (rétablit du code libGDX standard supprimé par l'obfuscation, aucune sémantique modifiée).
 */
public class DataInput extends DataInputStream {
    private char[] chars = new char[32];

    public DataInput(InputStream in) {
        super(in);
    }

    /** Lit un entier à longueur variable (encodage libGDX). optimizePositive=false → zig-zag. */
    public int readInt(boolean optimizePositive) throws IOException {
        int b = read();
        int result = b & 0x7F;
        if ((b & 0x80) != 0) {
            b = read();
            result |= (b & 0x7F) << 7;
            if ((b & 0x80) != 0) {
                b = read();
                result |= (b & 0x7F) << 14;
                if ((b & 0x80) != 0) {
                    b = read();
                    result |= (b & 0x7F) << 21;
                    if ((b & 0x80) != 0) {
                        b = read();
                        result |= (b & 0x7F) << 28;
                    }
                }
            }
        }
        return optimizePositive ? result : ((result >>> 1) ^ -(result & 1));
    }

    /** Lit une chaîne préfixée par sa longueur (var-int) ; 0 → null, 1 → "". */
    public String readString() throws IOException {
        int charCount = readInt(true);
        switch (charCount) {
            case 0:
                return null;
            case 1:
                return "";
        }
        charCount--;
        if (chars.length < charCount) chars = new char[charCount];
        char[] chars = this.chars;
        int charIndex = 0;
        int b = 0;
        while (charIndex < charCount) {
            b = read();
            if (b > 127) break;
            chars[charIndex++] = (char) b;
        }
        if (charIndex < charCount) readUtf8_slow(charCount, charIndex, b);
        return new String(chars, 0, charCount);
    }

    private void readUtf8_slow(int charCount, int charIndex, int b) throws IOException {
        char[] chars = this.chars;
        while (true) {
            switch (b >> 4) {
                case 0: case 1: case 2: case 3: case 4: case 5: case 6: case 7:
                    chars[charIndex] = (char) b;
                    break;
                case 12: case 13:
                    chars[charIndex] = (char) ((b & 0x1F) << 6 | read() & 0x3F);
                    break;
                case 14:
                    chars[charIndex] = (char) ((b & 0x0F) << 12 | (read() & 0x3F) << 6 | read() & 0x3F);
                    break;
            }
            charIndex++;
            if (charIndex >= charCount) break;
            b = read() & 0xFF;
        }
    }
}

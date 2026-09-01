package com.perblue.dhlauncher;

import java.security.MessageDigest;

/**
 * Ed25519 (RFC 8032) PUR-JAVA, sans dépendance — pour Android <b>API 26</b> où {@code Signature.getInstance("Ed25519")}
 * n'existe pas (API 33+). Port fidèle de <b>TweetNaCl</b> (Bernstein/Van Gundy et al., <b>domaine public</b>) : arithmétique
 * de corps en base 2^16 ({@code gf = long[16]}), {@code scalarbase}/{@code scalarmult}, {@code crypto_sign}/{@code
 * crypto_sign_open}. Le seul écart au port original : {@code SHA-512} via {@link MessageDigest} du JDK/Android (présent
 * partout) plutôt que la SHA-512 embarquée de TweetNaCl — même sortie.
 *
 * <p>PRINCIPLES §4 : rien d'inventé — dérivation/signature Ed25519 STANDARD. La parité <b>bit-à-bit</b> avec l'Ed25519 du
 * JDK (SunEC, utilisé côté serveur par {@code dhserver.auth.MnemonicIdentity}) est PROUVÉE par {@code
 * server/smoke/MobileIdentityParityTest} : une même phrase donne le MÊME userID/clé publique des deux côtés, et les
 * signatures se vérifient en croisé. Clés/ signatures = format RAW (pub 32 o, sig 64 o) ; le wrapping X.509 (SPKI 44 o) et
 * le userID sont dans {@link MobileIdentity}.
 */
public final class Ed25519 {
    private Ed25519() {}

    // ---- API publique -------------------------------------------------------------------------------------------
    /** seed (32 o = clé privée RFC 8032) → clé publique RAW (32 o). */
    public static byte[] publicKeyFromSeed(byte[] seed32) {
        byte[] pk = new byte[32];
        byte[] d = sha512(seed32);
        d[0] &= 248; d[31] &= 127; d[31] |= 64;
        long[][] p = new long[4][16];
        scalarbase(p, d);
        pack(pk, p);
        return pk;
    }

    /** Signe {@code msg} avec la clé privée = {@code seed32} (RFC 8032, déterministe) → signature RAW (64 o). */
    public static byte[] sign(byte[] seed32, byte[] msg) {
        byte[] pk = publicKeyFromSeed(seed32);
        byte[] d = sha512(seed32);
        d[0] &= 248; d[31] &= 127; d[31] |= 64;

        int n = msg.length;
        byte[] sm = new byte[64 + n];
        System.arraycopy(msg, 0, sm, 64, n);
        System.arraycopy(d, 32, sm, 32, 32);           // prefix (2nd moitié du hash du seed)

        byte[] r = sha512(sm, 32, 64 + n);             // r = H(prefix || msg) = sm[32..64+n)
        long[] x = new long[64];
        reduce(r);
        long[][] p = new long[4][16];
        scalarbase(p, r);
        pack(sm, p);                                    // R = r·B → sm[0..31]

        System.arraycopy(pk, 0, sm, 32, 32);            // sm = R || A || msg
        byte[] h = sha512(sm, 0, 64 + n);               // k = H(R || A || msg)
        reduce(h);

        for (int i = 0; i < 64; i++) x[i] = 0;
        for (int i = 0; i < 32; i++) x[i] = r[i] & 0xffL;
        for (int i = 0; i < 32; i++)
            for (int j = 0; j < 32; j++)
                x[i + j] += (h[i] & 0xffL) * (d[j] & 0xffL);   // S = r + k·a
        modL(sm, 32, x);                                // écrit S dans sm[32..63]

        byte[] sig = new byte[64];
        System.arraycopy(sm, 0, sig, 0, 64);
        return sig;
    }

    /** Vérifie une signature RAW (64 o) de {@code msg} contre la clé publique RAW (32 o). */
    public static boolean verify(byte[] pub32, byte[] msg, byte[] sig64) {
        if (sig64.length != 64) return false;
        long[][] q = new long[4][16];
        if (unpackneg(q, pub32) != 0) return false;

        int n = msg.length;
        byte[] m = new byte[64 + n];
        System.arraycopy(sig64, 0, m, 0, 64);
        System.arraycopy(pub32, 0, m, 32, 32);
        System.arraycopy(msg, 0, m, 64, n);

        byte[] h = sha512(m, 0, 64 + n);
        reduce(h);
        long[][] p = new long[4][16];
        scalarmult(p, q, h);

        long[][] r = new long[4][16];
        scalarbase(r, sig64Slice(sig64));
        add(p, r);
        byte[] t = new byte[32];
        pack(t, p);

        for (int i = 0; i < 32; i++) m[i] = sig64[i];   // R attendu
        return crypto_verify_32(t, m);
    }

    private static byte[] sig64Slice(byte[] sig64) {
        byte[] s = new byte[32];
        System.arraycopy(sig64, 32, s, 0, 32);
        return s;
    }

    // ---- corps arithmétique (TweetNaCl, base 2^16) --------------------------------------------------------------
    private static final long[] gf0 = new long[16];
    private static final long[] gf1 = { 1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0 };
    private static final long[] D = {
        0x78a3,0x1359,0x4dca,0x75eb,0xd8ab,0x4141,0x0a4d,0x0070,
        0xe898,0x7779,0x4079,0x8cc7,0xfe73,0x2b6f,0x6cee,0x5203 };
    private static final long[] D2 = {
        0xf159,0x26b2,0x9b94,0xebd6,0xb156,0x8283,0x149a,0x00e0,
        0xd130,0xeef3,0x80f2,0x198e,0xfce7,0x56df,0xd9dc,0x2406 };
    private static final long[] X = {
        0xd51a,0x8f25,0x2d60,0xc956,0xa7b2,0x9525,0xc760,0x692c,
        0xdc5c,0xfdd6,0xe231,0xc0a4,0x53fe,0xcd6e,0x36d3,0x2169 };
    private static final long[] Y = {
        0x6658,0x6666,0x6666,0x6666,0x6666,0x6666,0x6666,0x6666,
        0x6666,0x6666,0x6666,0x6666,0x6666,0x6666,0x6666,0x6666 };
    private static final long[] I = {
        0xa0b0,0x4a0e,0x1b27,0xc4ee,0xe478,0xad2f,0x1806,0x2f43,
        0xd7a7,0x3dfb,0x0099,0x2b4d,0xdf0b,0x4fc1,0x2480,0x2b83 };
    private static final long[] L = {
        0xed,0xd3,0xf5,0x5c,0x1a,0x63,0x12,0x58,0xd6,0x9c,0xf7,0xa2,0xde,0xf9,0xde,0x14,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0x10 };

    private static void set25519(long[] r, long[] a) { for (int i = 0; i < 16; i++) r[i] = a[i]; }

    private static void car25519(long[] o) {
        for (int i = 0; i < 16; i++) {
            o[i] += (1L << 16);
            long c = o[i] >> 16;
            o[(i + 1) * (i < 15 ? 1 : 0)] += c - 1 + 37 * (c - 1) * (i == 15 ? 1 : 0);
            o[i] -= c << 16;
        }
    }

    private static void sel25519(long[] p, long[] q, int b) {
        long c = ~(b - 1);
        for (int i = 0; i < 16; i++) {
            long t = c & (p[i] ^ q[i]);
            p[i] ^= t; q[i] ^= t;
        }
    }

    private static void pack25519(byte[] o, long[] n) {
        long[] m = new long[16], t = new long[16];
        for (int i = 0; i < 16; i++) t[i] = n[i];
        car25519(t); car25519(t); car25519(t);
        for (int j = 0; j < 2; j++) {
            m[0] = t[0] - 0xffed;
            for (int i = 1; i < 15; i++) {
                m[i] = t[i] - 0xffff - ((m[i - 1] >> 16) & 1);
                m[i - 1] &= 0xffff;
            }
            m[15] = t[15] - 0x7fff - ((m[14] >> 16) & 1);
            long b = (m[15] >> 16) & 1;
            m[14] &= 0xffff;
            sel25519(t, m, (int) (1 - b));
        }
        for (int i = 0; i < 16; i++) {
            o[2 * i] = (byte) (t[i] & 0xff);
            o[2 * i + 1] = (byte) (t[i] >> 8);
        }
    }

    private static int neq25519(long[] a, long[] b) {
        byte[] c = new byte[32], d = new byte[32];
        pack25519(c, a); pack25519(d, b);
        return crypto_verify_32(c, d) ? 0 : 1;
    }

    private static int par25519(long[] a) {
        byte[] d = new byte[32];
        pack25519(d, a);
        return d[0] & 1;
    }

    private static void unpack25519(long[] o, byte[] n) {
        for (int i = 0; i < 16; i++) o[i] = (n[2 * i] & 0xffL) + ((long) (n[2 * i + 1] & 0xff) << 8);
        o[15] &= 0x7fff;
    }

    private static void A(long[] o, long[] a, long[] b) { for (int i = 0; i < 16; i++) o[i] = a[i] + b[i]; }
    private static void Z(long[] o, long[] a, long[] b) { for (int i = 0; i < 16; i++) o[i] = a[i] - b[i]; }

    private static void M(long[] o, long[] a, long[] b) {
        long[] t = new long[31];
        for (int i = 0; i < 31; i++) t[i] = 0;
        for (int i = 0; i < 16; i++)
            for (int j = 0; j < 16; j++) t[i + j] += a[i] * b[j];
        for (int i = 0; i < 15; i++) t[i] += 38 * t[i + 16];
        for (int i = 0; i < 16; i++) o[i] = t[i];
        car25519(o); car25519(o);
    }

    private static void S(long[] o, long[] a) { M(o, a, a); }

    private static void inv25519(long[] o, long[] i) {
        long[] c = new long[16];
        for (int a = 0; a < 16; a++) c[a] = i[a];
        for (int a = 253; a >= 0; a--) {
            S(c, c);
            if (a != 2 && a != 4) M(c, c, i);
        }
        for (int a = 0; a < 16; a++) o[a] = c[a];
    }

    private static void pow2523(long[] o, long[] i) {
        long[] c = new long[16];
        for (int a = 0; a < 16; a++) c[a] = i[a];
        for (int a = 250; a >= 0; a--) {
            S(c, c);
            if (a != 1) M(c, c, i);
        }
        for (int a = 0; a < 16; a++) o[a] = c[a];
    }

    private static void add(long[][] p, long[][] q) {
        long[] a = new long[16], b = new long[16], c = new long[16], d = new long[16],
               t = new long[16], e = new long[16], f = new long[16], g = new long[16], h = new long[16];
        Z(a, p[1], p[0]); Z(t, q[1], q[0]); M(a, a, t);
        A(b, p[0], p[1]); A(t, q[0], q[1]); M(b, b, t);
        M(c, p[3], q[3]); M(c, c, D2);
        M(d, p[2], q[2]); A(d, d, d);
        Z(e, b, a); Z(f, d, c); A(g, d, c); A(h, b, a);
        M(p[0], e, f); M(p[1], h, g); M(p[2], g, f); M(p[3], e, h);
    }

    private static void cswap(long[][] p, long[][] q, byte b) {
        for (int i = 0; i < 4; i++) sel25519(p[i], q[i], b);
    }

    private static void pack(byte[] r, long[][] p) {
        long[] tx = new long[16], ty = new long[16], zi = new long[16];
        inv25519(zi, p[2]);
        M(tx, p[0], zi);
        M(ty, p[1], zi);
        pack25519(r, ty);
        r[31] ^= par25519(tx) << 7;
    }

    private static void scalarmult(long[][] p, long[][] q, byte[] s) {
        set25519(p[0], gf0); set25519(p[1], gf1); set25519(p[2], gf1); set25519(p[3], gf0);
        for (int i = 255; i >= 0; i--) {
            byte b = (byte) ((s[i / 8] >> (i & 7)) & 1);
            cswap(p, q, b);
            add(q, p);
            add(p, p);
            cswap(p, q, b);
        }
    }

    private static void scalarbase(long[][] p, byte[] s) {
        long[][] q = new long[4][16];
        set25519(q[0], X); set25519(q[1], Y); set25519(q[2], gf1);
        M(q[3], X, Y);
        scalarmult(p, q, s);
    }

    private static int unpackneg(long[][] r, byte[] p) {
        long[] t = new long[16], chk = new long[16], num = new long[16],
               den = new long[16], den2 = new long[16], den4 = new long[16], den6 = new long[16];
        set25519(r[2], gf1);
        unpack25519(r[1], p);
        S(num, r[1]);
        M(den, num, D);
        Z(num, num, r[2]);
        A(den, r[2], den);
        S(den2, den);
        S(den4, den2);
        M(den6, den4, den2);
        M(t, den6, num);
        M(t, t, den);
        pow2523(t, t);
        M(t, t, num);
        M(t, t, den);
        M(t, t, den);
        M(r[0], t, den);
        S(chk, r[0]);
        M(chk, chk, den);
        if (neq25519(chk, num) != 0) M(r[0], r[0], I);
        S(chk, r[0]);
        M(chk, chk, den);
        if (neq25519(chk, num) != 0) return -1;
        if (par25519(r[0]) == ((p[31] & 0xff) >> 7)) Z(r[0], gf0, r[0]);
        M(r[3], r[0], r[1]);
        return 0;
    }

    // ---- réduction scalaire mod L (ordre du groupe) -------------------------------------------------------------
    private static void modL(byte[] r, int off, long[] x) {
        long carry;
        for (int i = 63; i >= 32; i--) {
            carry = 0;
            int j, k;
            for (j = i - 32, k = i - 12; j < k; j++) {
                x[j] += carry - 16 * x[i] * L[j - (i - 32)];
                carry = (x[j] + 128) >> 8;
                x[j] -= carry << 8;
            }
            x[j] += carry;
            x[i] = 0;
        }
        carry = 0;
        for (int j = 0; j < 32; j++) {
            x[j] += carry - (x[31] >> 4) * L[j];
            carry = x[j] >> 8;
            x[j] &= 255;
        }
        for (int j = 0; j < 32; j++) x[j] -= carry * L[j];
        for (int i = 0; i < 32; i++) {
            x[i + 1] += x[i] >> 8;
            r[off + i] = (byte) (x[i] & 255);
        }
    }

    private static void reduce(byte[] r) {
        long[] x = new long[64];
        for (int i = 0; i < 64; i++) x[i] = r[i] & 0xffL;
        for (int i = 0; i < 64; i++) r[i] = 0;
        modL(r, 0, x);
    }

    // ---- comparaison constante ----------------------------------------------------------------------------------
    private static boolean crypto_verify_32(byte[] x, byte[] y) {
        int d = 0;
        for (int i = 0; i < 32; i++) d |= (x[i] ^ y[i]) & 0xff;
        return d == 0;
    }

    // ---- SHA-512 via le JDK/Android -----------------------------------------------------------------------------
    private static byte[] sha512(byte[] in) { return sha512(in, 0, in.length); }
    /** Hash de {@code in[off..end)} (end EXCLU). */
    private static byte[] sha512(byte[] in, int off, int end) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            md.update(in, off, end - off);
            return md.digest();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}

package com.m365bleapp.ffi;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;

/** Host JVM contract test: these names/signatures resolve the actual exported JNI symbols. */
public final class M365Native {
    private native void init();
    private native byte[] prepareHandshake();
    private native byte[] processHandshake(long handle, byte[] key, byte[] info);
    private native byte[] login(byte[] token, byte[] appRandom, byte[] deviceRandom, byte[] info);
    private native byte[] encrypt(long handle, byte[] payload, long counter);
    private native byte[] decrypt(long handle, byte[] frame);
    private native void freeSession(long handle);
    private static int checks;
    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        checks++;
    }
    private static byte[] hex(String s) { return HexFormat.of().parseHex(s); }
    private static long handle(byte[] bytes) { return ByteBuffer.wrap(bytes).getLong(); }
    private static byte[] sequence(int start, int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) bytes[i] = (byte)(start + i);
        return bytes;
    }
    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        M365Native n = new M365Native();
        n.init();
        byte[] a = n.prepareHandshake(), b = n.prepareHandshake();
        check(a.length == 73 && a[8] == 4, "handle + uncompressed SEC1 public key");
        check(handle(a) > 0 && handle(a) != handle(b), "unique big-endian handles");
        byte[] info = hex("00000000414243444546");
        byte[] keyA = Arrays.copyOfRange(a, 8, a.length);
        byte[] keyB = Arrays.copyOfRange(b, 8, b.length);
        byte[] resultA = n.processHandshake(handle(a), keyB, info);
        byte[] resultB = n.processHandshake(handle(b), keyA, info);
        check(resultA.length == 22 && Arrays.equals(resultA, resultB), "symmetric ECDH token + DID");
        check(n.processHandshake(handle(a), keyB, info).length == 0, "one-shot handshake");
        check(n.processHandshake(-1, keyB, info).length == 0, "forged handshake");
        byte[] invalid = n.prepareHandshake();
        check(n.processHandshake(handle(invalid), new byte[1], info).length == 0, "malformed public key");
        check(n.processHandshake(handle(invalid), keyB, info).length == 0, "failed handshake consumed");
        byte[] shortInfo = n.prepareHandshake();
        check(n.processHandshake(handle(shortInfo), keyB, new byte[4]).length == 0, "truncated DID rejected");
        check(n.login(new byte[11], new byte[16], new byte[16], new byte[0]).length == 0, "invalid token length");
        check(n.login(null, new byte[16], new byte[16], new byte[0]).length == 0, "null token");
        byte[] login = n.login(sequence(0,12), sequence(0,16), sequence(16,16), new byte[0]);
        check(login.length == 40 && handle(login) > 0, "login handle + HMAC length");
        check(Arrays.equals(Arrays.copyOfRange(login,8,40), hex("6ead8b835115a14a79957476c5fac6e2956a896ee23955b35218efd40d684631")), "independent login HMAC vector");
        long session = handle(login);
        byte[] payload = hex("052301b03412");
        for (long counter : new long[] {-1, 65536, 0x100000000L, Long.MAX_VALUE}) {
            check(n.encrypt(session, payload, counter).length == 0, "counter rejected: " + counter);
        }
        check(n.encrypt(session, new byte[0], 1).length == 0, "empty payload");
        check(n.encrypt(0, payload, 1).length == 0, "zero session");
        for (long counter : new long[] {0, 42, 65535}) {
            byte[] encrypted = n.encrypt(session, payload, counter);
            check(encrypted.length == payload.length + 14, "encrypted frame length");
            check(encrypted[0] == 0x55 && encrypted[1] == (byte)0xAB && encrypted[2] == payload[0], "frame header");
            check((encrypted[3] & 255) == (counter & 255) &&
                (encrypted[4] & 255) == (counter >>> 8), "little-endian counter");
            int sum = 0;
            for (int i = 2; i < encrypted.length-2; i++) sum += encrypted[i] & 255;
            int crc = (encrypted[encrypted.length-2] & 255) | ((encrypted[encrypted.length-1] & 255) << 8);
            check(crc == (~sum & 65535), "frame checksum");
        }
        // Fixed synthetic device-direction vector produced independently with Python cryptography AESCCM.
        byte[] frame = hex("55ab052a0007d02a7b32ede9e8480f2cd73dcdf9");
        byte[] expected = hex("2301b03412aabbccdd");
        check(Arrays.equals(n.decrypt(session, frame), expected), "device key and IV decrypt known vector");
        byte[] corrupt = frame.clone(); corrupt[6] ^= 1;
        check(n.decrypt(session, corrupt).length == 0, "corrupted ciphertext");
        // Repair checksum, leaving a bad CCM tag: integrity rejection must also come from AES.
        int sum = 0;
        for (int i=2; i<corrupt.length-2; i++) sum += corrupt[i] & 255;
        int crc = ~sum;
        corrupt[corrupt.length-2] = (byte)crc; corrupt[corrupt.length-1] = (byte)(crc >>> 8);
        check(n.decrypt(session, corrupt).length == 0, "bad CCM tag with valid checksum");
        for (int length=0; length<frame.length; length++) {
            check(n.decrypt(session, Arrays.copyOf(frame,length)).length == 0, "truncated frame " + length);
        }
        // Each Java thread obtains its own JNIEnv. Releasing concurrently is safe;
        // decrypt may return either the complete plaintext or an empty failure.
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                for (int i=0; i<200; i++) {
                    byte[] result = n.decrypt(session, frame);
                    if (result.length != 0 && !Arrays.equals(result, expected)) throw new AssertionError("torn session");
                }
            } catch (Throwable t) { failure.set(t); }
        });
        worker.start();
        n.freeSession(session);
        worker.join();
        if (failure.get() != null) throw new AssertionError("concurrent JNI", failure.get());
        check(n.decrypt(session, frame).length == 0, "use after free rejected");
        n.freeSession(session); n.freeSession(0); n.freeSession(-1);
        byte[] next = n.login(sequence(0,12), sequence(0,16), sequence(16,16), new byte[0]);
        check(handle(next) != session, "freed handle never reused");
        check(Arrays.equals(n.decrypt(handle(next), frame), expected), "new session unaffected");
        n.freeSession(handle(next));
        System.out.println("JNI contract: " + checks + " checks passed");
    }
}

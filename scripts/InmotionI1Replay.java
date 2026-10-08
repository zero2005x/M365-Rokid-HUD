import io.github.zero2005x.pev.core.codec.inmotioni1.InmotionI1Codec;
import io.github.zero2005x.pev.core.telemetry.Evidence;
import java.nio.file.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Independently written raw-envelope replay; no GPL/vendor implementation or field formulas. */
public final class InmotionI1Replay {
    static final class Counts {
        int frames, escapedChecksums, malformedBytes, overflows, physicalFields;
        final Map<Long,Integer> identifiers = new TreeMap<>();
        final Map<String,Integer> reasons = new TreeMap<>();
        final MessageDigest framesHash;
        Counts() throws Exception { framesHash = MessageDigest.getInstance("SHA-256"); }
        void accept(List<InmotionI1Codec.Event> events) {
            for (var event : events) {
                if (event instanceof InmotionI1Codec.Event.Frame frame) {
                    var packet = frame.getPacket();
                    frames++;
                    if (packet.getChecksumEscaped()) escapedChecksums++;
                    identifiers.merge(packet.getCanId(), 1, Integer::sum);
                    framesHash.update(packet.getRaw());
                    physicalFields += packet.getTelemetry().getFields().size();
                    if (packet.getInputEvidence() != Evidence.WIRE_CAPTURED) throw new AssertionError("evidence lost");
                } else if (event instanceof InmotionI1Codec.Event.Malformed bad) {
                    malformedBytes += bad.getRaw().length;
                    reasons.merge(bad.getReason(), 1, Integer::sum);
                } else if (event instanceof InmotionI1Codec.Event.Overflow) overflows++;
            }
        }
        String json() {
            var ids = new ArrayList<String>();
            identifiers.forEach((id,count) -> ids.add(String.format("\"%08X\":%d", id, count)));
            var failures = new ArrayList<String>();
            reasons.forEach((reason,count) -> failures.add("\""+reason+"\":"+count));
            return "{\"frames\":"+frames+",\"escapedChecksums\":"+escapedChecksums+
                ",\"malformedBytes\":"+malformedBytes+",\"overflows\":"+overflows+
                ",\"physicalFields\":"+physicalFields+",\"frameStreamSha256\":\""+
                HexFormat.of().formatHex(framesHash.digest())+"\",\"canIds\":{"+
                String.join(",", ids)+"},\"diagnosticReasons\":{"+String.join(",", failures)+"}}";
        }
    }
    static List<byte[]> segments(Path path) throws Exception {
        var values = new ArrayList<byte[]>();
        try (var input = new DataInputStream(Files.newInputStream(path))) {
            while (input.available() > 0) {
                int length = input.readUnsignedShort();
                var bytes = input.readNBytes(length);
                if (bytes.length != length) throw new EOFException("truncated transformed segment");
                values.add(bytes);
            }
        }
        return values;
    }
    static String replay(List<byte[]> segments, int segmentation) throws Exception {
        var codec = new InmotionI1Codec(512, 2048, Evidence.WIRE_CAPTURED);
        var counts = new Counts();
        if (segmentation == 0) {
            for (var bytes : segments) counts.accept(codec.feed(bytes));
        } else if (segmentation == 1) {
            for (var bytes : segments) for (byte value : bytes) counts.accept(codec.feed(new byte[]{value}));
        } else {
            var combined = new ByteArrayOutputStream();
            for (var bytes : segments) combined.write(bytes);
            counts.accept(codec.feed(combined.toByteArray()));
        }
        if (counts.overflows != 0 || counts.physicalFields != 0) throw new AssertionError("raw envelope contract broken");
        return counts.json();
    }
    public static void main(String[] args) throws Exception {
        var results = new ArrayList<String>();
        for (String name : List.of("V5F", "V8S", "alerts")) {
            var values = segments(Path.of(args[0],name+".segments"));
            String original = replay(values,0);
            String bytewise = replay(values,1);
            String coalesced = replay(values,2);
            if (!original.equals(bytewise) || !original.equals(coalesced)) throw new AssertionError("segmentation changes result: "+name);
            results.add("\""+name+"\":{\"original\":"+original+",\"bytewise\":"+bytewise+",\"coalesced\":"+coalesced+"}");
        }
        System.out.println("{\"sourceKind\":\"historical-community-reference\",\"hardwareAcceptance\":false,\"traces\":{"+String.join(",",results)+"}}");
    }
}

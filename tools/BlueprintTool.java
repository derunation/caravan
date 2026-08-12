import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Development tool: rewrites the mctradepost "Trade Post" station blueprints so
 * they place the caravan hut instead of the train station.
 *
 * <p>The blueprints are gzipped standard NBT. Every string is stored as a
 * big-endian SHORT length followed by UTF-8 bytes (either as a compound field
 * value or as a list element). Each replacement is verified against the length
 * prefix so substring false-positives are skipped, and the length prefix is
 * updated when the replacement differs in size. All replacements are applied
 * from the end of the buffer backwards so earlier offsets stay valid.</p>
 *
 * <p>Usage: {@code java BlueprintTool.java <inputDir> <outputDir>}</p>
 */
public final class BlueprintTool
{
    private BlueprintTool()
    {
    }

    public static void main(final String[] args) throws IOException
    {
        if (args.length == 4 && args[0].equals("hexdump"))
        {
            final byte[] data = gunzip(Files.readAllBytes(Path.of(args[1])));
            final int from = Integer.parseInt(args[2]);
            final int length = Integer.parseInt(args[3]);
            hexdump(data, from, length);
            return;
        }
        if (args.length == 2 && args[0].equals("inspect"))
        {
            inspect(Path.of(args[1]));
            return;
        }
        if (args.length != 2 && args.length != 4)
        {
            System.err.println("Usage: java BlueprintTool.java <inputDir> <outputDir> [inputNamePrefix] [outputNamePrefix]");
            System.exit(1);
        }

        final Path inputDir = Path.of(args[0]);
        final Path outputDir = Path.of(args[1]);
        final String inputName = args.length == 4 ? args[2] : "station";
        final String outputName = args.length == 4 ? args[3] : "caravanleader";
        Files.createDirectories(outputDir);

        for (int level = 1; level <= 5; level++)
        {
            final Path input = inputDir.resolve(inputName + level + ".blueprint");
            final Path output = outputDir.resolve(outputName + level + ".blueprint");
            if (!Files.exists(input))
            {
                System.err.println("Missing input: " + input);
                continue;
            }

            final byte[] decompressed = gunzip(Files.readAllBytes(input));
            final byte[] rewritten = rewrite(decompressed, level);
            Files.write(output, gzip(rewritten));

            System.out.println("Wrote " + output + " (" + rewritten.length + " bytes decompressed)");
        }
    }

    private static byte[] rewrite(final byte[] data, final int level)
    {
        // (old string, new string) pairs, longest first.
        final List<byte[][]> patterns = new ArrayList<>();
        patterns.add(new byte[][] {
            bytes("mctradepost:mctp_colonybuilding"), bytes("caravan:blockhutcaravanleader")
        });
        patterns.add(new byte[][] {
            bytes("mctradepost:blockhutstation"), bytes("caravan:blockhutcaravanleader")
        });
        patterns.add(new byte[][] {
            bytes("mctradepost:station"), bytes("caravan:caravanleader")
        });
        patterns.add(new byte[][] {
            bytes("trade post/economic/station" + level + ".blueprint"),
            bytes("trade post/economic/caravanleader" + level + ".blueprint")
        });
        patterns.add(new byte[][] {
            bytes("station" + level), bytes("caravanleader" + level)
        });
        patterns.add(new byte[][] {
            bytes("Trade Post"), bytes("Caravan")
        });
        patterns.add(new byte[][] {
            bytes("trade post/economic/caravanleader" + level + ".blueprint"),
            bytes("caravan/craftsmanship/caravanleader" + level + ".blueprint")
        });
        patterns.add(new byte[][] {
            bytes("caravan/craftsmanship/caravanleader" + level + ".blueprint"),
            bytes("caravan/craftsmanship/storage/caravanleader" + level + ".blueprint")
        });
        patterns.add(new byte[][] {
            bytes("mctradepost"), bytes("caravan")
        });

        // (string start, old length, replacement index)
        final List<int[]> replacements = new ArrayList<>();
        for (int i = 0; i < patterns.size(); i++)
        {
            collect(data, patterns.get(i)[0], i, replacements);
        }

        // Apply from the end backwards so earlier offsets remain valid.
        replacements.sort((a, b) -> Integer.compare(b[0], a[0]));

        byte[] current = data.clone();
        int applied = 0;
        for (final int[] replacement : replacements)
        {
            final int stringStart = replacement[0];
            final int oldLen = replacement[1];
            final byte[] newValue = patterns.get(replacement[2])[1];

            // The string must be preceded by its own SHORT length prefix.
            final int storedLen = stringStart >= 2
                ? ((current[stringStart - 2] & 0xFF) << 8) | (current[stringStart - 1] & 0xFF)
                : -1;
            if (storedLen != oldLen)
            {
                continue;
            }

            final byte[] result = new byte[current.length - oldLen + newValue.length];
            System.arraycopy(current, 0, result, 0, stringStart);
            System.arraycopy(newValue, 0, result, stringStart, newValue.length);
            System.arraycopy(current, stringStart + oldLen, result, stringStart + newValue.length, current.length - stringStart - oldLen);

            result[stringStart - 2] = (byte) ((newValue.length >> 8) & 0xFF);
            result[stringStart - 1] = (byte) (newValue.length & 0xFF);

            // Later replacements are at earlier offsets, so swapping the buffer is safe.
            current = result;
            applied++;
        }

        System.out.println("Applied " + applied + " replacement(s)");
        return current;
    }

    private static void collect(final byte[] data, final byte[] pattern, final int patternIndex, final List<int[]> out)
    {
        int from = 0;
        while (true)
        {
            final int index = indexOf(data, pattern, from);
            if (index < 0)
            {
                return;
            }
            // tag: 0=string start offset, 1=old length, 2=replacement index
            out.add(new int[] {index, pattern.length, patternIndex});
            from = index + pattern.length;
        }
    }

    private static byte[] bytes(final String value)
    {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static int indexOf(final byte[] data, final byte[] pattern, final int from)
    {
        outer:
        for (int i = from; i <= data.length - pattern.length; i++)
        {
            for (int j = 0; j < pattern.length; j++)
            {
                if (data[i + j] != pattern[j])
                {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static byte[] gunzip(final byte[] data) throws IOException
    {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data));
             ByteArrayOutputStream out = new ByteArrayOutputStream())
        {
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    private static byte[] gzip(final byte[] data) throws IOException
    {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             GZIPOutputStream out = new GZIPOutputStream(bytes))
        {
            out.write(data);
            out.finish();
            return bytes.toByteArray();
        }
    }

    private static String hex(final byte[] data, final int from, final int to)
    {
        final int start = Math.max(0, from);
        final int end = Math.min(data.length, to);
        final StringBuilder builder = new StringBuilder();
        for (int i = start; i < end; i++)
        {
            builder.append(String.format("%02X ", data[i]));
        }
        return builder.toString().trim();
    }

    private static void inspect(final Path file) throws IOException
    {
        final byte[] data = gunzip(Files.readAllBytes(file));
        System.out.println("File: " + file + ", decompressed size: " + data.length);
        System.out.println("Header: " + hex(data, 0, 32));

        // Find every printable ASCII run of length >= 4 and print the byte before it.
        int i = 0;
        while (i < data.length)
        {
            final int start = i;
            while (i < data.length && data[i] >= 32 && data[i] < 127)
            {
                i++;
            }
            if (i - start >= 4)
            {
                final String text = new String(data, start, i - start, StandardCharsets.UTF_8);
                final byte before = start > 0 ? data[start - 1] : -1;
                final byte before2 = start > 1 ? data[start - 2] : -1;
                final byte before3 = start > 2 ? data[start - 3] : -1;
                System.out.printf("0x%02X 0x%02X 0x%02X @ %6d  %s%n", before3, before2, before, start, text);
            }
            i++;
        }
    }

    private static void hexdump(final byte[] data, final int from, final int length)
    {
        for (int offset = from; offset < Math.min(data.length, from + length); offset += 16)
        {
            final StringBuilder hex = new StringBuilder();
            final StringBuilder ascii = new StringBuilder();
            for (int i = 0; i < 16 && offset + i < data.length; i++)
            {
                final byte b = data[offset + i];
                hex.append(String.format("%02X ", b));
                ascii.append(b >= 32 && b < 127 ? (char) b : '.');
            }
            System.out.printf("%6d  %-48s  %s%n", offset, hex, ascii);
        }
    }
}

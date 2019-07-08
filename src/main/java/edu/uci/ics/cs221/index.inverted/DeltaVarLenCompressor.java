package edu.uci.ics.cs221.index.inverted;

import java.util.ArrayList;
import java.util.List;

/**
 * Implement this compressor with Delta Encoding and Variable-Length Encoding.
 * See Project 3 description for details.
 */
public class DeltaVarLenCompressor implements Compressor {

    @Override
    public byte[] encode(List<Integer> integers) {
        List<Integer> deltaEncoded = new ArrayList<>();
        deltaEncoded.add(integers.get(0));
        for (int i = 0; i < integers.size() - 1; i++) {
            deltaEncoded.add(integers.get(i + 1) - integers.get(i));
        }
        List<Byte> result = new ArrayList<>();
        for (int i = 0; i < deltaEncoded.size(); i++) {
            List<Byte> curNumByte = new ArrayList<>();
            encodeInteger(deltaEncoded.get(i), curNumByte);
            for (int j = curNumByte.size() - 1; j >= 0; j--) {
                result.add(curNumByte.get(j));
            }
        }
        byte[] encodedByteArray = new byte[result.size()];
        for (int i = 0; i < result.size(); i++) encodedByteArray[i] = result.get(i);
        return encodedByteArray;
    }

    private void encodeInteger(int a, List<Byte> encodedList) {
        int mod = a % 128;
        encodedList.add((byte) mod);
        int res = a / 128;
        while (res >= 1) {
            int resMod = res % 128 + 128;
            encodedList.add((byte) resMod);
            res /= 128;
        }
    }

    @Override
    public List<Integer> decode(byte[] bytes, int start, int length) {
        List<Integer> result = new ArrayList<>();
        int totalLength = start + length;
        int i = start;
        int offset = 0;
        while (i < totalLength) {
            int num = 0;
            while (bytes[i] < 0) {
                int cur = num + 128 + bytes[i];
                num = cur * 128;
                i++;
            }
            num += bytes[i];
            offset += num;
            result.add(offset);
            i++;
        }
        return result;
    }
}

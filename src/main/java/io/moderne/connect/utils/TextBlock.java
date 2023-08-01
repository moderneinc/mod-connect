package io.moderne.connect.utils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

public final class TextBlock {
    public static String textBlock(String resource) {
        int bufferSize = 1024;
        char[] buffer = new char[bufferSize];
        StringBuilder out = new StringBuilder();
        Reader in = new InputStreamReader(TextBlock.class.getClassLoader().getResourceAsStream(resource),
                StandardCharsets.UTF_8);
        try {
            for (int numRead; (numRead = in.read(buffer, 0, buffer.length)) > 0; ) {
                out.append(buffer, 0, numRead);
            }
        } catch (IOException e) {
            throw new RuntimeException("Can not read " + resource, e);
        }
        return out.toString();
    }

    private TextBlock() {
    }
}

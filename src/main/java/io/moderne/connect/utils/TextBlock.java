/**
 * Copyright 2023 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.moderne.connect.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

public final class TextBlock {
    public static String textBlock(String resource) {
        int bufferSize = 1024;
        char[] buffer = new char[bufferSize];
        StringBuilder out = new StringBuilder();

        if (resource == null) {
            throw new IllegalStateException("Resource is null");
        }

        InputStream is = TextBlock.class.getClassLoader().getResourceAsStream(resource);

        if (is == null) {
            throw new IllegalStateException("Resource not found: " + resource);
        }

        Reader in = new InputStreamReader(is, StandardCharsets.UTF_8);
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

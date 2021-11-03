/*
this implementations contains significant code from https://github.com/ngs-doo/dsl-json/blob/master/LICENSE

Copyright (c) 2015, Nova Generacija Softvera d.o.o.
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

    * Redistributions of source code must retain the above copyright notice,
      this list of conditions and the following disclaimer.

    * Redistributions in binary form must reproduce the above copyright notice,
      this list of conditions and the following disclaimer in the documentation
      and/or other materials provided with the distribution.

    * Neither the name of Nova Generacija Softvera d.o.o. nor the names of its
      contributors may be used to endorse or promote products derived from this
      software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.jsoniter;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.Arrays;

class IterImplString {

    private final static int[] sHexValues = new int[128];

    static {
        Arrays.fill(sHexValues, -1);
        for (int i = 0; i < 10; ++i) {
            sHexValues['0' + i] = i;
        }
        for (int i = 0; i < 6; ++i) {
            sHexValues['a' + i] = 10 + i;
            sHexValues['A' + i] = 10 + i;
        }
    }

    final static int[] hexDigits = new int['f' + 1];

    static {
        for (int i = 0; i < hexDigits.length; i++) {
            hexDigits[i] = -1;
        }
        for (int i = '0'; i <= '9'; ++i) {
            hexDigits[i] = (i - '0');
        }
        for (int i = 'a'; i <= 'f'; ++i) {
            hexDigits[i] = ((i - 'a') + 10);
        }
        for (int i = 'A'; i <= 'F'; ++i) {
            hexDigits[i] = ((i - 'A') + 10);
        }
    }

    public static final String readString(JsonIterator iter) throws IOException {
        byte c = IterImpl.nextToken(iter);
        if (c != '"') {
            if (c == 'n') {
                IterImpl.skipFixedBytes(iter, 3);
                return null;
            }
            throw iter.reportError("readString", "expect string or null, but " + (char) c);
        }
        int j = parse(iter);
        return new String(iter.reusableChars, 0, j);
    }

    private static int parse(JsonIterator iter) throws IOException {
        byte c;// try fast path first
        int i = iter.head;
        // this code will trigger jvm hotspot pattern matching to highly optimized assembly
        int bound = iter.reusableChars.length;
        bound = IterImpl.updateStringCopyBound(iter, bound);
        for (int j = 0; j < bound; j++) {
            c = iter.buf[i++];
            if (c == '"') {
                iter.head = i;
                return j;
            }
            // If we encounter a backslash, which is a beginning of an escape sequence
            // or a high bit was set - indicating an UTF-8 encoded multibyte character,
            // there is no chance that we can decode the string without instantiating
            // a temporary buffer, so quit this loop
            if ((c ^ '\\') < 1) {
                break;
            }
            iter.reusableChars[j] = (char) c;
        }
        int alreadyCopied = 0;
        if (i > iter.head) {
            alreadyCopied = i - iter.head - 1;
            iter.head = i - 1;
        }
        return IterImpl.readStringSlowPath(iter, alreadyCopied);
    }

    public static int translateHex(final byte b) {
        int val = hexDigits[b];
        if (val == -1) {
            throw new IndexOutOfBoundsException(b + " is not valid hex digit");
        }
        return val;
    }

    // slice does not allow escape
    final static int findSliceEnd(JsonIterator iter) {
        for (int i = iter.head; i < iter.tail; i++) {
            byte c = iter.buf[i];
            if (c == '"') {
                return i + 1;
            } else if (c == '\\') {
                throw iter.reportError("findSliceEnd", "slice does not support escape char");
            }
        }
        return -1;
    }

    // slice does not allow escape
    final static FindSliceEndResponse findSliceEnd2(JsonIterator iter, ByteBuffer cache) {
        for (int i = iter.head; i < iter.tail; i++) {
            byte c = iter.buf[i];
            if (c == '"') {
                return new FindSliceEndResponse(i + 1);
            } else if (c == '\\') {
                return findSliceEndSpecailChar(iter, i, cache);
            }
        }
        return null;
    }

    private static FindSliceEndResponse findSliceEndSpecailChar(JsonIterator iter, int specialcharIndex, ByteBuffer cache) {
        cache.put(iter.buf, iter.head, specialcharIndex - iter.head);

        for (int i = specialcharIndex; i < iter.tail; i++) {
            byte c = iter.buf[i];
            if (c == '"') {
                cache.flip();
                byte[] newarray = new byte[cache.limit()];
                cache.get(newarray, 0, newarray.length);
                return new FindSliceEndResponse(newarray, i + 1);
            } else if (c == '\\') {
                byte nextc = iter.buf[i + 1];
                switch (nextc) {
                    // First, ones that are mapped
                    case 'b':
                        i++;
                        cache.put((byte) '\b');
                        break;
                    case 't':
                        i++;
                        cache.put((byte) '\t');
                        break;
                    case 'n':
                        i++;
                        cache.put((byte) '\n');
                        break;
                    case 'f':
                        i++;
                        cache.put((byte) '\f');
                        break;
                    case 'r':
                        i++;
                        cache.put((byte) '\r');
                        break;
                    case '"':
                    case '/':
                    case '\\':
                        i++;
                        cache.put((byte) nextc);
                        break;
                    case 'u': // and finally hex-escaped
                        // Ok, a hex escape. Need 4 characters
                        int value = 0;
                        for (int ui = 0; ui < 4; ++ui) {
                            int digit = sHexValues[iter.buf[i + 2 + ui]];
                            if (digit < 0) {
                                throw new RuntimeException("expected a hex-digit for character escape sequence");
                            }
                            value = (value << 4) | digit;
                        }

                        if (0 <= value
                                && value <= Byte.MAX_VALUE) {
                            cache.put((byte) value);
                        } else {
                            try {
                                cache.put(("" + ((char) value)).getBytes("utf-8"));
                            } catch (UnsupportedEncodingException e) {
                                throw new RuntimeException();
                            }
                        }

                        i += 5;

                        break;

                    default:
                        throw new IllegalArgumentException("char=" + nextc);
                }
            } else {
                cache.put(c);
            }
        }

        return null;
    }

    public static class FindSliceEndResponse {
        byte[] bytes;
        int end;

        public FindSliceEndResponse(int end) {
            this.end = end;
        }

        public FindSliceEndResponse(byte[] bytes, int end) {
            this.bytes = bytes;
            this.end = end;
        }

        public int getEnd() {
            return end;
        }

        public byte[] getBytes() {
            return bytes;
        }
    }

}

package com.imagemacro;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 아주 작은 JSON 읽기/쓰기.
 * 안드로이드의 org.json 에 기대지 않아서 PC 단위 테스트에서도 똑같이 동작한다.
 *
 * 읽은 값의 타입: 객체 = Map, 배열 = List, 문자열 = String, 숫자 = Double, 참/거짓 = Boolean, null = null
 */
public final class Json {

    private Json() {
    }

    /** 잘못된 JSON 이면 IllegalArgumentException (메시지에 위치가 들어 있음). */
    public static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("JSON 이 비어 있습니다");
        }
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.pos != text.length()) {
            throw p.error("끝에 남은 글자가 있습니다");
        }
        return v;
    }

    public static String stringify(Object value, boolean pretty) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, pretty, 0);
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 느슨하게 꺼내 쓰는 도우미 (없거나 타입이 달라도 기본값)
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<>();
    }

    public static String str(Map<String, Object> m, String key, String def) {
        Object o = m.get(key);
        return o instanceof String ? (String) o : def;
    }

    public static int integer(Map<String, Object> m, String key, int def) {
        Object o = m.get(key);
        return o instanceof Number ? (int) Math.round(((Number) o).doubleValue()) : def;
    }

    public static boolean bool(Map<String, Object> m, String key, boolean def) {
        Object o = m.get(key);
        return o instanceof Boolean ? (Boolean) o : def;
    }

    // ------------------------------------------------------------------
    // 쓰기
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object v, boolean pretty, int depth) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            quote(sb, (String) v);
        } else if (v instanceof Boolean) {
            sb.append(v.toString());
        } else if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
        } else if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                newline(sb, pretty, depth + 1);
                quote(sb, e.getKey());
                sb.append(pretty ? ": " : ":");
                write(sb, e.getValue(), pretty, depth + 1);
            }
            newline(sb, pretty, depth);
            sb.append('}');
        } else if (v instanceof List) {
            List<Object> l = (List<Object>) v;
            if (l.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                newline(sb, pretty, depth + 1);
                write(sb, l.get(i), pretty, depth + 1);
            }
            newline(sb, pretty, depth);
            sb.append(']');
        } else {
            throw new IllegalArgumentException("JSON 으로 쓸 수 없는 타입: " + v.getClass().getName());
        }
    }

    private static void newline(StringBuilder sb, boolean pretty, int depth) {
        if (!pretty) {
            return;
        }
        sb.append('\n');
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------
    // 읽기
    // ------------------------------------------------------------------

    private static final class Parser {
        final String s;
        int pos = 0;

        Parser(String s) {
            this.s = s;
        }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException("JSON 오류 (" + pos + "번째 글자): " + msg);
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '﻿') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object value() {
            if (pos >= s.length()) {
                throw error("값이 필요한데 끝났습니다");
            }
            char c = s.charAt(pos);
            if (c == '{') {
                return object();
            }
            if (c == '[') {
                return array();
            }
            if (c == '"') {
                return string();
            }
            if (s.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            return number();
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; // {
            skipWs();
            if (peek() == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                if (peek() != '"') {
                    throw error("키(따옴표 문자열)가 필요합니다");
                }
                String key = string();
                skipWs();
                if (peek() != ':') {
                    throw error("':' 가 필요합니다");
                }
                pos++;
                skipWs();
                m.put(key, value());
                skipWs();
                char c = peek();
                pos++;
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    pos--;
                    throw error("',' 또는 '}' 가 필요합니다");
                }
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            pos++; // [
            skipWs();
            if (peek() == ']') {
                pos++;
                return l;
            }
            while (true) {
                skipWs();
                l.add(value());
                skipWs();
                char c = peek();
                pos++;
                if (c == ']') {
                    return l;
                }
                if (c != ',') {
                    pos--;
                    throw error("',' 또는 ']' 가 필요합니다");
                }
            }
        }

        String string() {
            StringBuilder sb = new StringBuilder();
            pos++; // 여는 따옴표
            while (true) {
                if (pos >= s.length()) {
                    throw error("문자열이 닫히지 않았습니다");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= s.length()) {
                    throw error("잘못된 이스케이프");
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case '"':
                    case '\\':
                    case '/':
                        sb.append(e);
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        if (pos + 4 > s.length()) {
                            throw error("잘못된 \\u 이스케이프");
                        }
                        try {
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw error("잘못된 \\u 이스케이프");
                        }
                        pos += 4;
                        break;
                    default:
                        throw error("알 수 없는 이스케이프 \\" + e);
                }
            }
        }

        Double number() {
            int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
                pos++;
            }
            if (start == pos) {
                throw error("알 수 없는 값");
            }
            try {
                return Double.valueOf(s.substring(start, pos));
            } catch (NumberFormatException e) {
                pos = start;
                throw error("숫자가 올바르지 않습니다");
            }
        }

        char peek() {
            if (pos >= s.length()) {
                throw error("예상보다 일찍 끝났습니다");
            }
            return s.charAt(pos);
        }
    }
}

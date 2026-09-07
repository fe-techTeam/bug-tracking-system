package com.bugtracking.service;

import java.util.List;

public final class CsvWriter {

    // Excel only detects UTF-8 when the file opens with a byte-order mark.
    public static final String BOM = "﻿";

    private CsvWriter() {
    }

    public static String row(List<String> fields) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(escape(fields.get(i)));
        }
        return out.append("\r\n").toString();
    }

    private static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        boolean risky = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        return risky ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }
}

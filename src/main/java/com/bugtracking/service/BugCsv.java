package com.bugtracking.service;

import com.bugtracking.model.Bug;

import java.util.ArrayList;
import java.util.List;

public final class BugCsv {

    private static final List<String> HEADER = List.of("id", "title", "severity", "status",
            "environment", "project", "module", "reported_by", "assignees", "labels",
            "due_date", "created_at", "updated_at");

    private BugCsv() {
    }

    public static String render(List<Bug> bugs, BoardColumns board) {
        StringBuilder out = new StringBuilder(CsvWriter.BOM);
        out.append(CsvWriter.row(HEADER));
        for (Bug b : bugs) {
            List<String> row = new ArrayList<>();
            row.add(String.valueOf(b.getId()));
            row.add(b.getTitle());
            row.add(b.getSeverity() == null ? null : b.getSeverity().name());
            row.add(board.label(b));
            row.add(b.getEnvironment() == null ? null : b.getEnvironment().name());
            row.add(b.getProject());
            row.add(b.getModule());
            row.add(b.getReportedBy());
            row.add(String.join(";", b.getAssignees()));
            row.add(String.join(";", b.getLabels()));
            row.add(b.getDueDate() == null ? null : b.getDueDate().toString());
            row.add(b.getCreatedAt() == null ? null : b.getCreatedAt().toString());
            row.add(b.getUpdatedAt() == null ? null : b.getUpdatedAt().toString());
            out.append(CsvWriter.row(row));
        }
        return out.toString();
    }
}

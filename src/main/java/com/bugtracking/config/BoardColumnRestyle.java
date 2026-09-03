package com.bugtracking.config;

import com.bugtracking.model.BoardColumn;
import com.bugtracking.model.DefaultColumns;
import com.bugtracking.repository.BoardColumnRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Repaints the boards nobody has touched, once, to the current defaults.
 *
 * <p>{@link DefaultColumns} only decides what a <em>new</em> project opens on.
 * Every project that already existed keeps the rows it has, which is right for
 * a board somebody has renamed and reordered and wrong for one still showing
 * the six this app happened to ship first — the point of changing a default is
 * that everybody who never chose otherwise gets it.
 *
 * <p>So: a board is rewritten only when it is <em>exactly</em> the old stock
 * set — same six keys, same six labels, same six colours. One edit anywhere in
 * it and the board is somebody's, and this leaves it alone entirely.
 *
 * <p>Only the label, the colour and the order change. The {@code status_key} a
 * bug stores is never touched, so no bug moves column and nothing has to be
 * remapped: "Retest" becoming "Testing" is a word on a column, not a new one.
 */
@Configuration
public class BoardColumnRestyle {

    private static final Logger log = LoggerFactory.getLogger(BoardColumnRestyle.class);

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 45)     // after BoardColumnSeed has given every project a board
    CommandLineRunner restyleStockBoards(BoardColumnRepository repository) {
        return args -> {
            // What the new defaults say, by key: the label, the colour and where
            // in the order it goes.
            List<BoardColumn> wanted = DefaultColumns.forProject("");
            Map<String, BoardColumn> byKey = new LinkedHashMap<>();
            wanted.forEach(column -> byKey.put(column.getStatusKey(), column));

            Map<String, List<BoardColumn>> boards = new LinkedHashMap<>();
            for (BoardColumn column : repository.findAllByOrderByProjectAscPositionAsc()) {
                boards.computeIfAbsent(column.getProject(), any -> new ArrayList<>()).add(column);
            }

            int repainted = 0;
            for (List<BoardColumn> board : boards.values()) {
                if (!isStock(board)) {
                    continue;
                }
                for (BoardColumn column : board) {
                    BoardColumn to = byKey.get(column.getStatusKey());
                    column.setLabel(to.getLabel());
                    column.setColour(to.getColour());
                    column.setPosition(to.getPosition());
                }
                repository.saveAll(board);
                repainted++;
            }

            if (repainted > 0) {
                log.info("Repainted {} untouched board(s) to the current default columns. "
                        + "Boards that had been edited were left as they were.", repainted);
            }
        };
    }

    /** True when this board is still, exactly, the set that shipped before. */
    private static boolean isStock(List<BoardColumn> board) {
        List<String[]> stock = DefaultColumns.stock();
        if (board.size() != stock.size()) {
            return false;
        }
        // Compared in the board's own order, which for an untouched board is the
        // order it was seeded in.
        for (int i = 0; i < stock.size(); i++) {
            BoardColumn column = board.get(i);
            String[] was = stock.get(i);
            if (!was[0].equals(column.getStatusKey())
                    || !was[1].equals(column.getLabel())
                    || column.getColour() == null
                    || !was[2].equals(column.getColour().name())) {
                return false;
            }
        }
        return true;
    }
}

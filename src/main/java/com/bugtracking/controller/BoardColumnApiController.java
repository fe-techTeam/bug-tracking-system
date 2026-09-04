package com.bugtracking.controller;

import com.bugtracking.service.BoardColumnService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The one column operation that is only reachable with JavaScript: dragging a
 * column head to a new place on the board.
 *
 * <p>It lives here rather than beside the forms in {@link BoardColumnController}
 * because a drag wants a 204 back, not a redirect to a page it has already
 * updated by hand. CSRF is <em>not</em> waived here any more — the API used to
 * be exempt on the grounds that it had no session to ride on, and closing it to
 * ROLE_USER gave it one — so the drag handler sends the token from
 * {@code layout.html}'s meta tag like every other fetch in app.js.
 *
 * <p>Everything reordering does is also reachable without it — the two arrows
 * in a column's menu post to the form routes and reload the board.
 */
@RestController
@RequestMapping("/api/columns")
public class BoardColumnApiController {

    private final BoardColumnService service;

    public BoardColumnApiController(BoardColumnService service) {
        this.service = service;
    }

    /**
     * Sets a board's column order from the ids, left to right.
     *
     * <p>The service ignores ids the project does not own and keeps any column
     * the list forgot, so a page left open while somebody else added a column
     * cannot drop it.
     */
    @PostMapping("/order")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reorder(@RequestParam String project,
                        @RequestParam(required = false) List<Long> ids) {
        service.reorder(project, ids);
    }
}

package com.bugtracking.controller;

import com.bugtracking.model.BoardColumn;
import com.bugtracking.model.ColumnColour;
import com.bugtracking.model.ColumnNotify;
import com.bugtracking.service.BoardColumnService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

/**
 * Adding, renaming, reordering and removing the columns of a board.
 *
 * <p>Every route here is an ordinary form POST that redirects, because all of
 * it has to work with JavaScript off — including reordering, which is what the
 * two arrows in a column's menu are for. Dragging a column head is the
 * enhancement on top, and posts the whole new order to
 * {@code BoardColumnApiController} instead, which is where CSRF is waived and
 * a 204 is more use than a redirect.
 *
 * <p>Where you land afterwards is the board you were editing. There used to be
 * a second, fuller editor behind Settings and a {@code from} switch to say
 * which of the two you had come from; the columns are edited on the board and
 * nowhere else now, so there is one destination and no switch. It is built
 * from the project name rather than taken as a return URL, so no request can
 * talk this into redirecting somewhere off-site.
 */
@Controller
@RequestMapping("/columns")
public class BoardColumnController {

    private final BoardColumnService service;

    public BoardColumnController(BoardColumnService service) {
        this.service = service;
    }

    /** Kept so a bookmarked or mistyped GET arrives somewhere useful. */
    @GetMapping
    public String list() {
        return "redirect:/bugs";
    }

    @PostMapping
    public String add(@RequestParam String project,
                      @RequestParam String label,
                      @RequestParam(required = false) String colour,
                      @RequestParam(defaultValue = "false") boolean done,
                      @RequestParam(required = false) String notify,
                      RedirectAttributes flash) {
        try {
            BoardColumn added = service.add(project, label, ColumnColour.of(colour),
                    done, ColumnNotify.of(notify));
            flash.addFlashAttribute("message", added.getLabel() + " is on the board.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("message", e.getMessage());
        }
        return back(project);
    }

    /**
     * Saves a column. Anything left out is kept as it was, so the rename box on
     * the board can post a name alone without resetting the colour.
     * Every field is optional for that reason — a colour swatch posts a colour
     * and nothing else, and a required "label" turned that into a 400.
     */
    @PostMapping("/{id}")
    public String edit(@PathVariable Long id,
                       @RequestParam(required = false) String label,
                       @RequestParam(required = false) String colour,
                       @RequestParam(required = false) Boolean done,
                       @RequestParam(required = false) String notify,
                       RedirectAttributes flash) {
        String project = service.findById(id).getProject();
        try {
            BoardColumn saved = service.edit(id, label,
                    colour == null ? null : ColumnColour.of(colour),
                    done,
                    notify == null ? null : ColumnNotify.of(notify));
            flash.addFlashAttribute("message", "Column saved as " + saved.getLabel() + ".");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("message", e.getMessage());
        }
        return back(project);
    }

    /** One place left or right — the route that needs no JavaScript. */
    @PostMapping("/{id}/move")
    public String move(@PathVariable Long id,
                       @RequestParam String direction) {
        // Nothing to announce: the board redraws in the new order, which is the
        // whole message.
        BoardColumn column = service.move(id, "left".equals(direction) ? -1 : 1);
        return back(column.getProject());
    }

    @PostMapping("/{id}/delete")
    public String remove(@PathVariable Long id,
                         @RequestParam(required = false) Long moveTo,
                         RedirectAttributes flash) {
        String project = service.findById(id).getProject();
        try {
            flash.addFlashAttribute("message", service.remove(id, moveTo));
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("message", e.getMessage());
        }
        return back(project);
    }

    /** The board you were editing, which is the only place these forms live. */
    private static String back(String project) {
        String encoded = project == null ? "" : UriUtils.encodeQueryParam(project, StandardCharsets.UTF_8);
        return "redirect:/bugs?project=" + encoded;
    }
}

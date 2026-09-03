package com.bugtracking.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.validation.constraints.Size;

/**
 * One column of one project's board.
 *
 * <p>These used to be a Java enum, which meant the six statuses this app
 * shipped with were the six statuses you got: renaming "Ready for Test",
 * adding a "Blocked" column or putting On Hold somewhere else on the track
 * were all recompiles. They are rows now, one set per project, because two
 * projects rarely run the same process — a client engagement wants Sign-off,
 * an internal tool does not.
 *
 * <p>Two fields carry the weight:
 *
 * <ul>
 *   <li>{@link #statusKey} is what a bug actually stores, and it never changes
 *       once written. Renaming a column rewrites {@link #label} and touches no
 *       bug at all, which is the whole point — a rename is a change of wording,
 *       not a change of where the work is.
 *   <li>{@link #position} is the order left to right. Nothing else encodes it:
 *       there is no lifecycle track in the code any more, only the order you
 *       put the columns in.
 * </ul>
 *
 * <p>The project is stored as its <em>name</em> rather than a foreign key, the
 * same way {@code Bug} stores it, so a set of columns survives the project row
 * being retired and matches a bug filed under a differently-cased spelling.
 */
@Entity
@Table(name = "board_columns",
       uniqueConstraints = @UniqueConstraint(name = "uk_column_project_key",
                                             columnNames = {"project", "status_key"}))
public class BoardColumn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String project;

    /**
     * What a bug stores. Uppercase, underscore-separated, unique within the
     * project, and immutable — see {@link #setLabel} for why the two are
     * separate at all.
     */
    @Column(name = "status_key", nullable = false, length = 40)
    private String statusKey;

    @NotBlank(message = "A column needs a name")
    @Size(max = 40, message = "A column name must be 40 characters or fewer")
    @Column(nullable = false, length = 40)
    private String label;

    /**
     * {@code @JdbcTypeCode} is not decoration. Without it Hibernate maps an
     * enum onto H2's native ENUM type, which writes today's constants into the
     * column type itself — so adding Red and Brown to {@link ColumnColour} made
     * every board fail with "Value not permitted for column", and ddl-auto will
     * not widen a type that already exists. VARCHAR remembers no list. Every
     * other enum in this model carries the same annotation for the same reason;
     * these two were missed.
     */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private ColumnColour colour = ColumnColour.SLATE;

    /**
     * Whether work in this column counts as finished.
     *
     * <p>This is the one piece of meaning the app still reads out of a column,
     * and it is load-bearing in four places: the "still open" count, the urgent
     * count, which bugs may be picked as a blocker, and the board's own summary.
     * It replaces {@code Status.isOpenWork()}, which could only ever answer for
     * the statuses that were compiled in.
     */
    @Column(name = "done_state", nullable = false)
    private boolean doneState;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private ColumnNotify notify = ColumnNotify.NOBODY;

    @Column(nullable = false)
    private int position;

    public BoardColumn() {
    }

    public BoardColumn(String project, String statusKey, String label,
                       ColumnColour colour, boolean doneState, ColumnNotify notify, int position) {
        this.project = project;
        this.statusKey = statusKey;
        this.label = label;
        this.colour = colour;
        this.doneState = doneState;
        this.notify = notify;
        this.position = position;
    }

    /** What a template puts in {@code --c}. */
    public String getToken() {
        return colour.getToken();
    }

    /** Counts as work in hand — the inverse of {@link #isDoneState()}. */
    public boolean isOpenWork() {
        return !doneState;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = project;
    }

    public String getStatusKey() {
        return statusKey;
    }

    /**
     * Only ever called when a column is created. There is deliberately no path
     * that changes a key on a column that already exists: every bug in the
     * column holds it, and every history entry was written against the label it
     * had at the time.
     */
    public void setStatusKey(String statusKey) {
        this.statusKey = statusKey;
    }

    public String getLabel() {
        return label;
    }

    /** Renaming is free — nothing but this row stores the label. */
    public void setLabel(String label) {
        this.label = label;
    }

    public ColumnColour getColour() {
        return colour;
    }

    public void setColour(ColumnColour colour) {
        this.colour = colour;
    }

    public boolean isDoneState() {
        return doneState;
    }

    public void setDoneState(boolean doneState) {
        this.doneState = doneState;
    }

    public ColumnNotify getNotify() {
        return notify;
    }

    public void setNotify(ColumnNotify notify) {
        this.notify = notify;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }
}

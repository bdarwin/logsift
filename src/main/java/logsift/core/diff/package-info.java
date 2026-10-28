/**
 * Comparing files: what is the same, what changed, what was added and removed.
 *
 * <p>The pieces every kind of comparison shares:
 * <ul>
 *   <li>{@link logsift.core.diff.DiffStatus}: what happened to one item (a row, a line, a hunk):
 *       same, changed, added, removed, or a duplicate key that can't be matched.</li>
 *   <li>{@link logsift.core.diff.Edit} and {@link logsift.core.diff.SequenceDiff}: two sequences of
 *       hashes lined up as runs of equal, inserted, deleted and replaced items (patience anchors,
 *       then Myers' algorithm in the gaps). Rows of a table without keys use it today; lines of
 *       text, words within a changed line, and both sides of a three-way merge (each against the
 *       base) use the same edits.</li>
 *   <li>{@link logsift.core.diff.ChangeNavigator}: next and previous change, for any list of items
 *       where some are changes.</li>
 *   <li>{@link logsift.core.diff.DiffOptions}: when two values count as equal (spaces, case,
 *       numbers within a tolerance, empty and missing).</li>
 * </ul>
 *
 * <p>Tables: {@link logsift.core.diff.CsvFile} reads a CSV file row by row and remembers only where
 * each row is; {@link logsift.core.diff.TableDiff} matches rows by key (a hash join that keeps
 * hashes and row positions, not rows) or, without keys, in file order; the
 * {@link logsift.core.diff.TableDiffResult} reads rows again when they are shown.
 */
package logsift.core.diff;

package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class LevelsTest {
    @Test
    void dotNetSpellings() {
        assertEquals(Levels.ERROR, Levels.normalize("fail"));
        assertEquals(Levels.ERROR, Levels.normalize("crit"));
        assertEquals(Levels.ERROR, Levels.normalize("ERR"));
        assertEquals(Levels.WARN, Levels.normalize("WRN"));
        assertEquals(Levels.INFO, Levels.normalize("Information"));
        assertEquals(Levels.DEBUG, Levels.normalize("dbug"));
        assertEquals(Levels.TRACE, Levels.normalize("trce"));
        assertEquals(Levels.TRACE, Levels.normalize("VRB"));
    }

    @Test
    void theCheckCoversEveryChoiceOfTheLevelGroup() {
        // a format of the user's whose level is a list of choices: each one is checked, not just the first
        LogFormat f = new LogFormat("mine", "^(?<level>INFO|ERROR|W(?:ARN)?) (?<message>[^)]*\\(x\\)|.*)$", true, null);
        Pattern errors = Pattern.compile(Levels.lineRegex(f, List.of("ERROR")));
        assertTrue(errors.matcher("ERROR broke").find());
        assertFalse(errors.matcher("INFO fine").find());
        assertFalse(errors.matcher("WARN slow").find());
        Pattern warnings = Pattern.compile(Levels.lineRegex(f, List.of("WARN")));
        assertTrue(warnings.matcher("W slow").find());
        assertFalse(warnings.matcher("ERROR broke").find());
    }

    @Test
    void groupsEndWhereTheyEnd() {
        assertEquals(10, Levels.closingParen("(?<level>a)b", 0));
        assertEquals(16, Levels.closingParen("(?<level>[)(]\\)x)", 0), "brackets and escapes don't count");
        assertEquals(-1, Levels.closingParen("(?<level>a", 0));
    }
}

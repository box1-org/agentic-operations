package org.box1.aiops.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class OutputValidatorTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Set<String> REFS = Set.of("ev_1", "ev_2");

    static String answer(String findings, String actions) {
        return "{\"summary\":\"s\",\"findings\":[" + findings + "],\"unknowns\":[\"u\"],\"suggestedActions\":["
            + actions + "]}";
    }

    @Test
    void keepsCitedFindingsAndAllowlistedActionsAsProposals() throws Exception {
        var v = OutputValidator.validate(JSON, answer(
            "{\"statement\":\"a\",\"evidenceRefs\":[\"ev_1\",\"ev_2\"],\"confidence\":\"HIGH\"}",
            "{\"type\":\"CHECK_BROKER\",\"description\":\"d\",\"expectedEffect\":\"e\",\"rollback\":\"r\"}"), REFS);
        assertEquals(1, v.findings().size());
        assertEquals(1, v.actions().size());
        assertFalse(v.actions().get(0).executable());
        assertEquals(0, v.quality().unsupportedAssertions());
    }

    @Test
    void dropsFindingsWithMissingOrInventedCitations() throws Exception {
        var v = OutputValidator.validate(JSON, answer(
            "{\"statement\":\"a\",\"evidenceRefs\":[],\"confidence\":\"LOW\"},"
                + "{\"statement\":\"b\",\"evidenceRefs\":[\"ev_1\",\"ev_7\"],\"confidence\":\"LOW\"},"
                + "{\"statement\":\"c\",\"evidenceRefs\":[\"https://evil\"],\"confidence\":\"LOW\"}", ""), REFS);
        assertEquals(0, v.findings().size());
        assertEquals(3, v.quality().unsupportedAssertions());
    }

    @Test
    void dropsActionsOutsideTheAllowlist() throws Exception {
        var v = OutputValidator.validate(JSON, answer("",
            "{\"type\":\"EXECUTE_ROLLBACK\",\"description\":\"d\",\"expectedEffect\":\"e\",\"rollback\":\"r\"}"),
            REFS);
        assertEquals(0, v.actions().size());
        assertEquals(1, v.quality().droppedActions());
    }

    @Test
    void rejectsStructurallyInvalidAnswers() {
        for (String bad : new String[] {"", "[]", "{\"summary\":1}", "{\"summary\":\"s\",\"findings\":[]}",
            answer("{\"statement\":\"a\",\"evidenceRefs\":[\"ev_1\"],\"confidence\":\"CERTAIN\"}", ""),
            "{\"summary\":\"" + "x".repeat(2001) + "\",\"findings\":[],\"unknowns\":[],\"suggestedActions\":[]}"}) {
            assertThrows(OutputValidator.Invalid.class, () -> OutputValidator.validate(JSON, bad, REFS), bad);
        }
    }
}

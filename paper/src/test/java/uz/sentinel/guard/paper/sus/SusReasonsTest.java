package uz.sentinel.guard.paper.sus;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SusReasonsTest {
    private static ConfigurationSection roundTrip(Map<String, Integer> reasons) throws Exception {
        YamlConfiguration out = new YamlConfiguration();
        SusManager.writeReasons(out.createSection("p"), reasons);
        YamlConfiguration in = new YamlConfiguration();
        in.loadFromString(out.saveToString());
        return in.getConfigurationSection("p");
    }

    @Test
    void reasonsWithDotsSurviveSaveAndLoad() throws Exception {
        Map<String, Integer> reasons = new LinkedHashMap<>();
        reasons.put("fly v2.0", 3);
        reasons.put("reach 3.5 blocks", 1);
        reasons.put("sentinelac: reach", 2);
        assertEquals(reasons, SusManager.readReasons(roundTrip(reasons)));
    }

    @Test
    void oldSectionFormatIsRecoveredIncludingSplitDots() throws Exception {
        // What 2.2.0 wrote for reasons "fly v2.0" (split at the dot) and "killaura".
        YamlConfiguration in = new YamlConfiguration();
        in.loadFromString("p:\n  reasons:\n    fly v2:\n      '0': 3\n    killaura: 2\n");
        Map<String, Integer> reasons = SusManager.readReasons(in.getConfigurationSection("p"));
        assertEquals(3, reasons.get("fly v2.0"));
        assertEquals(2, reasons.get("killaura"));
        assertEquals(2, reasons.size());
    }

    @Test
    void missingReasonsAreEmpty() {
        YamlConfiguration in = new YamlConfiguration();
        assertTrue(SusManager.readReasons(in.createSection("p")).isEmpty());
    }
}
